package com.v2ray.ang.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ProxyChain
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChainChoice(val id: String, val name: String)
data class ChainSettingsState(
    val loading: Boolean = true,
    val groups: List<ChainChoice> = emptyList(),
    val entries: List<ChainChoice> = emptyList(),
    val nodes: List<ChainChoice> = emptyList(),
    val groupId: String = "",
    val entryId: String = "",
    val exitId: String = "",
    val previousId: String = "",
    val enabled: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: Int? = null
)

sealed interface ChainSettingsAction {
    data class Group(val id: String) : ChainSettingsAction
    data class Entry(val id: String) : ChainSettingsAction
    data class Exit(val id: String) : ChainSettingsAction
    data class Previous(val id: String) : ChainSettingsAction
    data class Mode(val enabled: Boolean) : ChainSettingsAction
    data object Save : ChainSettingsAction
    data object Reload : ChainSettingsAction
}

/** Only this screen's repository boundary; production storage remains owned by MMKV. */
interface ChainSettingsRepository {
    fun profiles(): List<Pair<String, ProfileItem>>
    fun groups(): List<Pair<String, SubscriptionItem>>
    fun group(id: String): SubscriptionItem?
    fun selected(): String?
    fun fragment(): Boolean
    fun save(id: String, group: SubscriptionItem, entry: String)
}

class MmkvChainSettingsRepository : ChainSettingsRepository {
    override fun profiles() = MmkvManager.decodeServerList().mapNotNull { id ->
        MmkvManager.decodeServerConfig(id)?.let { id to it }
    }
    override fun groups() = MmkvManager.decodeSubscriptions()
    override fun group(id: String) = MmkvManager.decodeProxyChainGroup(id)
    override fun selected() = MmkvManager.getSelectServer()
    override fun fragment() = MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false) == true
    override fun save(id: String, group: SubscriptionItem, entry: String) =
        MmkvManager.saveProxyChainGroup(id, group, entry)
}

class ChainSettingsViewModel(
    private val savedState: SavedStateHandle,
    private val repository: ChainSettingsRepository = MmkvChainSettingsRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChainSettingsState())
    val state: StateFlow<ChainSettingsState> = mutableState.asStateFlow()
    private var work: Job? = null

    init { load(savedState["group"]) }

    fun onAction(action: ChainSettingsAction) {
        if (mutableState.value.saving) return
        when (action) {
            is ChainSettingsAction.Group -> {
                savedState["group"] = action.id
                listOf("entry", "exit", "previous", "enabled").forEach { savedState.remove<Any>(it) }
                load(action.id)
            }
            is ChainSettingsAction.Entry -> update("entry", action.id) { it.copy(entryId = action.id) }
            is ChainSettingsAction.Exit -> update("exit", action.id) { it.copy(exitId = action.id) }
            is ChainSettingsAction.Previous -> update("previous", action.id) { it.copy(previousId = action.id) }
            is ChainSettingsAction.Mode -> update("enabled", action.enabled) { it.copy(enabled = action.enabled) }
            ChainSettingsAction.Save -> save()
            ChainSettingsAction.Reload -> {
                if (mutableState.value.error == R.string.chain_error_group) {
                    listOf("group", "entry", "exit", "previous", "enabled").forEach { savedState.remove<Any>(it) }
                    load(null)
                } else load(savedState["group"])
            }
        }
    }

    private fun update(key: String, value: Any, transform: (ChainSettingsState) -> ChainSettingsState) {
        savedState[key] = value
        mutableState.value = transform(mutableState.value).copy(error = null, saved = false)
    }

    private fun load(requestedGroup: String?) {
        work?.cancel()
        val restoredEntry: String? = savedState["entry"]
        val restoredExit: String? = savedState["exit"]
        val restoredPrevious: String? = savedState["previous"]
        val restoredEnabled: Boolean? = savedState["enabled"]
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    val all = repository.profiles()
                    val selected = repository.selected()
                    val groupId = requestedGroup ?: all.find { it.first == selected }?.second?.subscriptionId.orEmpty()
                    val groups = listOf(ChainChoice("", "")) + repository.groups().map { ChainChoice(it.first, it.second.remarks) }
                    if (groups.none { it.id == groupId }) throw ProxyChain.Invalid(R.string.chain_error_group)
                    val group = repository.group(groupId) ?: SubscriptionItem()
                    val supported = all.filter { ProxyChain.supported(it.second) }
                    fun choices(nodes: List<Pair<String, ProfileItem>>) = nodes.map {
                        ChainChoice(it.first, "${it.second.remarks} · ${it.second.configType.name}")
                    }
                    var resolutionError: Int? = null
                    fun legacy(id: String?, alias: String?): String {
                        if (!id.isNullOrBlank()) return id
                        if (alias.isNullOrBlank()) return ""
                        val matches = all.filter { it.second.remarks == alias }
                        if (matches.size != 1) resolutionError = if (matches.isEmpty())
                            R.string.chain_error_missing else R.string.chain_error_ambiguous
                        return matches.singleOrNull()?.first.orEmpty()
                    }
                    val entries = supported.filter { it.second.subscriptionId == groupId }
                    val exitId = restoredExit ?: legacy(group.nextProfileId, group.nextProfile)
                    val previousId = restoredPrevious ?: legacy(group.prevProfileId, group.prevProfile)
                    if ((restoredEnabled ?: ProxyChain.enabled(group)) &&
                        listOf(exitId, previousId).any { id -> id.isNotEmpty() && supported.none { it.first == id } })
                        resolutionError = R.string.chain_error_missing
                    ChainSettingsState(loading = false, groups = groups, entries = choices(entries),
                        nodes = choices(supported), groupId = groupId,
                        entryId = restoredEntry ?: entries.find { it.first == selected }?.first ?: entries.firstOrNull()?.first.orEmpty(),
                        exitId = exitId,
                        previousId = previousId,
                        enabled = restoredEnabled ?: ProxyChain.enabled(group), error = resolutionError)
                }
                savedState["group"] = loaded.groupId
                mutableState.value = loaded
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                LogUtil.failure("load-chain-settings", "chain-ui", requestedGroup.orEmpty(), e)
                mutableState.value = mutableState.value.copy(loading = false,
                    error = (e as? ProxyChain.Invalid)?.resource ?: R.string.chain_error_load)
            }
        }
    }

    private fun save() {
        val input = mutableState.value
        if (input.loading || input.saving) return
        mutableState.value = input.copy(saving = true, error = null, saved = false)
        work = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val profiles = repository.profiles()
                    val entry = profiles.find { it.first == input.entryId }?.second
                        ?: throw ProxyChain.Invalid(R.string.chain_error_entry)
                    if (entry.subscriptionId != input.groupId) throw ProxyChain.Invalid(R.string.chain_error_group)
                    if (input.enabled && input.exitId.isBlank()) throw ProxyChain.Invalid(R.string.chain_error_exit)
                    val existing = repository.group(input.groupId)
                    if (input.groupId.isNotEmpty() && existing == null) throw ProxyChain.Invalid(R.string.chain_error_group)
                    val group = (existing ?: SubscriptionItem()).copy(chainEnabled = input.enabled,
                        nextProfileId = input.exitId.ifBlank { null }, prevProfileId = input.previousId.ifBlank { null },
                        nextProfile = null, prevProfile = null)
                    ProxyChain.resolve(input.entryId, entry, group, profiles, repository.fragment())
                    repository.save(input.groupId, group, input.entryId)
                }
                mutableState.value = input.copy(saving = false, saved = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                LogUtil.failure("save-chain-settings", "chain-ui", input.groupId, e)
                mutableState.value = input.copy(saving = false,
                    error = (e as? ProxyChain.Invalid)?.resource ?: R.string.chain_error_save)
            }
        }
    }
}

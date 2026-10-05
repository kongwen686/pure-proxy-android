package com.v2ray.ang.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

class ChainSettingsActivity : BaseComponentActivity() {
    private val model: ChainSettingsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ChainSettingsViewModel::class.java)
                return modelClass.cast(ChainSettingsViewModel(extras.createSavedStateHandle()))!!
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = Utils.getDarkModeStatus(this)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsStateWithLifecycle()
                ChainSettingsScreen(state, model::onAction) { finish() }
            }
        }
    }
}

@Composable
fun ChainSettingsScreen(state: ChainSettingsState, action: (ChainSettingsAction) -> Unit, close: () -> Unit) {
    var picker by rememberSaveable { mutableStateOf("") }
    val unspecified = stringResource(R.string.chain_choose_node)
    fun name(id: String, choices: List<ChainChoice>): String = choices.find { it.id == id }?.name ?: unspecified
    val ungrouped = stringResource(R.string.chain_ungrouped)
    val groupChoices = state.groups.map { if (it.id.isEmpty()) it.copy(name = ungrouped) else it }
    Scaffold { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.chain_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.chain_description))
            if (state.loading) CircularProgressIndicator()
            else {
                OutlinedButton(onClick = { picker = "group" }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chain_group_value, name(state.groupId, groupChoices)))
                }
                listOf(false to R.string.chain_mode_normal, true to R.string.chain_mode_static).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.enabled == value, role = Role.RadioButton,
                        enabled = !state.saving, onClick = { action(ChainSettingsAction.Mode(value)) }).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = state.enabled == value, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedButton(onClick = { picker = "entry" }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chain_entry_value, name(state.entryId, state.entries)))
                }
                if (state.entries.isEmpty()) Text(stringResource(R.string.chain_empty), color = MaterialTheme.colorScheme.error)
                if (state.enabled) {
                    OutlinedButton(onClick = { picker = "exit" }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chain_exit_value, name(state.exitId, state.nodes)))
                    }
                    OutlinedButton(onClick = { picker = "previous" }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chain_previous_value,
                            if (state.previousId.isEmpty()) stringResource(R.string.chain_none) else name(state.previousId, state.nodes)))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.chain_preview_title), style = MaterialTheme.typography.titleMedium)
                    if (state.previousId.isEmpty())
                        Text(stringResource(R.string.chain_preview, name(state.entryId, state.entries), name(state.exitId, state.nodes)))
                    else Text(stringResource(R.string.chain_preview_front, name(state.previousId, state.nodes),
                        name(state.entryId, state.entries), name(state.exitId, state.nodes)))
                    Text(stringResource(R.string.chain_scope), style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.saved) Text(stringResource(R.string.chain_saved), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { action(ChainSettingsAction.Save) },
                    enabled = !state.saving && state.entries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.saving) R.string.chain_saving else R.string.chain_save))
                }
                TextButton(onClick = { action(ChainSettingsAction.Reload) }, enabled = !state.saving) {
                    Text(stringResource(R.string.chain_reload))
                }
            }
            TextButton(onClick = close, enabled = !state.saving) { Text(stringResource(R.string.chain_back)) }
        }
    }
    if (picker.isNotEmpty()) {
        val choices = when (picker) {
            "group" -> groupChoices
            "entry" -> state.entries
            "previous" -> listOf(ChainChoice("", stringResource(R.string.chain_none))) + state.nodes
            else -> state.nodes
        }
        val selected = when (picker) {
            "group" -> state.groupId
            "entry" -> state.entryId
            "previous" -> state.previousId
            else -> state.exitId
        }
        AlertDialog(onDismissRequest = { picker = "" }, title = { Text(stringResource(R.string.chain_choose_node)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { choice -> key(choice.id) {
                        Row(Modifier.fillMaxWidth().selectable(selected = choice.id == selected, role = Role.RadioButton,
                            onClick = {
                                action(when (picker) {
                                    "group" -> ChainSettingsAction.Group(choice.id)
                                    "entry" -> ChainSettingsAction.Entry(choice.id)
                                    "previous" -> ChainSettingsAction.Previous(choice.id)
                                    else -> ChainSettingsAction.Exit(choice.id)
                                }); picker = ""
                            }).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = choice.id == selected, onClick = null)
                            Text(choice.name, modifier = Modifier.padding(top = 12.dp))
                        }
                    } }
                }
            }, confirmButton = { TextButton(onClick = { picker = "" }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

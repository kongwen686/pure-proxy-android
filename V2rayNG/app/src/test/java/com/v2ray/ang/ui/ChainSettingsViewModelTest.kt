package com.v2ray.ang.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.MockedStatic
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class ChainSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var log: MockedStatic<Log>
    @Before fun setup() { Dispatchers.setMain(dispatcher); log=Mockito.mockStatic(Log::class.java) }
    @After fun cleanup() { log.close(); Dispatchers.resetMain() }
    private class Repo : ChainSettingsRepository {
        var nodes = mutableListOf("entry" to ProfileItem(configType=EConfigType.VLESS,subscriptionId="group",
            remarks="relay",server="server.example",serverPort="443"),
            "exit" to ProfileItem(configType=EConfigType.SOCKS,subscriptionId="isp",remarks="exit",
                server="exit.example",serverPort="466"))
        var records = mutableMapOf("group" to SubscriptionItem(remarks="Relay group"),
            "isp" to SubscriptionItem(remarks="Exit group"))
        var selectedId = "entry"
        var badLoad = false
        var badSave = false
        var fragments = false
        var savedCount = 0
        override fun profiles(): List<Pair<String,ProfileItem>> {
            if(badLoad) throw IllegalStateException("disk failure")
            return nodes.toList()
        }
        override fun groups() = records.map {it.key to it.value.copy()}
        override fun group(id: String) = records[id]?.copy()
        override fun selected() = selectedId
        override fun fragment() = fragments
        override fun save(id: String, group: SubscriptionItem, entry: String) {
            if(badSave) throw IllegalStateException("disk failure")
            records[id] = group.copy(); selectedId = entry; savedCount++
        }
    }
    private fun model(repo:Repo,handle:SavedStateHandle=SavedStateHandle()) =
        ChainSettingsViewModel(handle,repo,dispatcher)

    @Test fun initialLoadingThenPopulatedAndEmptyStates() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo)
        assertTrue(model.state.value.loading); advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertEquals("group",model.state.value.groupId)
        assertEquals("entry",model.state.value.entryId)
        assertFalse(model.state.value.enabled)
        repo.nodes.clear(); model.onAction(ChainSettingsAction.Reload); advanceUntilIdle()
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun chainSavePersistsGuidAndReopeningRestoresIt() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo); advanceUntilIdle()
        model.onAction(ChainSettingsAction.Mode(true))
        model.onAction(ChainSettingsAction.Exit("exit"))
        model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertTrue(model.state.value.saved)
        assertEquals("exit",repo.records["group"]?.nextProfileId)
        assertNull(repo.records["group"]?.nextProfile)
        val reopened=model(repo); advanceUntilIdle()
        assertTrue(reopened.state.value.enabled)
        assertEquals("exit",reopened.state.value.exitId)
    }

    @Test fun modeSwitchKeepsExitSelectionButDisablesChain() = runTest(dispatcher) {
        val repo=Repo(); repo.records["group"] = SubscriptionItem(chainEnabled=true,nextProfileId="exit")
        val model=model(repo); advanceUntilIdle()
        model.onAction(ChainSettingsAction.Mode(false)); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(false,repo.records["group"]?.chainEnabled)
        assertEquals("exit",repo.records["group"]?.nextProfileId)
    }

    @Test fun missingEntryExitAndLoopErrorsDoNotWrite() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo); advanceUntilIdle()
        model.onAction(ChainSettingsAction.Mode(true)); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_exit,model.state.value.error)
        model.onAction(ChainSettingsAction.Exit("entry")); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_loop,model.state.value.error)
        model.onAction(ChainSettingsAction.Entry("gone")); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_entry,model.state.value.error)
        assertEquals(0,repo.savedCount)
    }

    @Test fun deletedNodeAndDeletedGroupAreRevalidatedAtSave() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo); advanceUntilIdle()
        model.onAction(ChainSettingsAction.Mode(true)); model.onAction(ChainSettingsAction.Exit("exit"))
        repo.nodes.removeAll { it.first=="exit" }
        model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_missing,model.state.value.error)
        repo.records.remove("group")
        model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_group,model.state.value.error)
        assertEquals(0,repo.savedCount)
    }

    @Test fun loadSaveFailureAndRetryPreserveSettings() = runTest(dispatcher) {
        val repo=Repo(); repo.badLoad=true
        val model=model(repo); advanceUntilIdle()
        assertEquals(R.string.chain_error_load,model.state.value.error)
        repo.badLoad=false; model.onAction(ChainSettingsAction.Reload); advanceUntilIdle()
        assertNull(model.state.value.error)
        model.onAction(ChainSettingsAction.Mode(true)); model.onAction(ChainSettingsAction.Exit("exit"))
        repo.badSave=true; model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_save,model.state.value.error)
        assertNull(repo.records["group"]?.chainEnabled)
        repo.badSave=false; model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertTrue(model.state.value.saved)
    }

    @Test fun savedDraftRestoresSelectionByDomainIdAfterReordering() = runTest(dispatcher) {
        val repo=Repo(); val handle=SavedStateHandle()
        val first=model(repo,handle); advanceUntilIdle()
        first.onAction(ChainSettingsAction.Mode(true)); first.onAction(ChainSettingsAction.Exit("exit"))
        repo.nodes.reverse()
        val recreated=model(repo,handle); advanceUntilIdle()
        assertEquals("entry",recreated.state.value.entryId)
        assertEquals("exit",recreated.state.value.exitId)
        assertTrue(recreated.state.value.enabled)
    }

    @Test fun groupChangeClearsDraftAndReadsThatGroupsSettings() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo); advanceUntilIdle()
        model.onAction(ChainSettingsAction.Mode(true)); model.onAction(ChainSettingsAction.Exit("exit"))
        model.onAction(ChainSettingsAction.Group("isp")); advanceUntilIdle()
        assertEquals("exit",model.state.value.entryId)
        assertEquals("",model.state.value.exitId)
        assertFalse(model.state.value.enabled)
    }

    @Test fun optionalFrontAndLegacyAliasMigrateToGuid() = runTest(dispatcher) {
        val repo=Repo()
        repo.nodes.add("front" to ProfileItem(configType=EConfigType.SOCKS,remarks="front",server="front.example",serverPort="1080"))
        repo.records["group"] = SubscriptionItem(nextProfile="exit")
        val model=model(repo); advanceUntilIdle()
        assertEquals("exit",model.state.value.exitId)
        model.onAction(ChainSettingsAction.Previous("front")); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals("front",repo.records["group"]?.prevProfileId)
        assertEquals("exit",repo.records["group"]?.nextProfileId)
    }

    @Test fun duplicateAliasCanBeRepairedBySelectingExactNode() = runTest(dispatcher) {
        val repo=Repo(); repo.nodes.add("duplicate" to repo.nodes.last().second.copy())
        repo.records["group"] = SubscriptionItem(nextProfile="exit")
        val model=model(repo); advanceUntilIdle()
        assertEquals(R.string.chain_error_ambiguous,model.state.value.error)
        assertTrue(model.state.value.entries.isNotEmpty())
        model.onAction(ChainSettingsAction.Exit("exit")); model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertTrue(model.state.value.saved)
    }

    @Test fun ungroupedNodesWorkWithoutCreatingSubscriptionRecords() = runTest(dispatcher) {
        val repo=Repo(); repo.nodes[0]=repo.nodes[0].first to repo.nodes[0].second.copy(subscriptionId="")
        val model=model(repo); advanceUntilIdle()
        assertEquals("",model.state.value.groupId)
        model.onAction(ChainSettingsAction.Mode(true)); model.onAction(ChainSettingsAction.Exit("exit"))
        model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertTrue(model.state.value.saved)
        assertEquals("exit",repo.records[""]?.nextProfileId)
    }

    @Test fun removedGroupRestorationCanRecoverThroughReload() = runTest(dispatcher) {
        val repo=Repo(); val model=model(repo,SavedStateHandle(mapOf("group" to "deleted")))
        advanceUntilIdle()
        assertEquals(R.string.chain_error_group,model.state.value.error)
        model.onAction(ChainSettingsAction.Reload); advanceUntilIdle()
        assertEquals("group",model.state.value.groupId)
        assertNull(model.state.value.error)
    }

    @Test fun unavailableSavedExitIsVisibleAndFragmentCannotSaveChain() = runTest(dispatcher) {
        val repo=Repo(); repo.records["group"]=SubscriptionItem(chainEnabled=true,nextProfileId="removed")
        val model=model(repo); advanceUntilIdle()
        assertEquals(R.string.chain_error_missing,model.state.value.error)
        model.onAction(ChainSettingsAction.Exit("exit")); repo.fragments=true
        model.onAction(ChainSettingsAction.Save); advanceUntilIdle()
        assertEquals(R.string.chain_error_fragment,model.state.value.error)
        assertEquals(0,repo.savedCount)
    }
}

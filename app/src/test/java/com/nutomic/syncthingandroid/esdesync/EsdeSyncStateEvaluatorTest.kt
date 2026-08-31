package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import com.nutomic.syncthingandroid.model.RemoteNeed
import com.nutomic.syncthingandroid.model.RemoteNeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdeSyncStateEvaluatorTest {
    private val healthy = EsdeFolderHealth("saves", false, "idle", "", 0, 0, 0, 0, 100, 0.0, 0)

    @Test fun readyOnlyWhenEveryGateIsClean() {
        assertEquals(EsdeSyncState.READY_TO_PLAY, evaluate(healthy))
        assertEquals(EsdeSyncState.WAITING_FOR_PRIMARY, evaluate(healthy, connected = false))
        assertEquals(EsdeSyncState.WAITING_FOR_PRIMARY, EsdeSyncStateEvaluator.evaluate(EsdeGateInput(true, true, true, true, listOf(healthy))))
        assertEquals(EsdeSyncState.ERROR, evaluate(healthy.copy(paused = true)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(needFiles = 1)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(needBytes = 1)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(remoteCompletion = 99)))
        assertEquals(
            EsdeSyncState.READY_TO_PLAY,
            evaluate(healthy.copy(remoteCompletion = 99, remoteNeedItems = 3, remoteNeedKnown = true, remoteIgnoredItems = 3)),
        )
        assertEquals(
            EsdeSyncState.SYNCING,
            evaluate(healthy.copy(remoteCompletion = 99, remoteNeedKnown = true, remoteBlockingItems = 1)),
        )
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(needTotalItems = 1)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(remoteNeedBytes = 1.0)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(remoteNeedItems = 1)))
        assertEquals(EsdeSyncState.SYNCING, evaluate(healthy.copy(remoteState = "paused")))
        assertEquals(EsdeSyncState.ERROR, evaluate(healthy.copy(conflicts = 1)))
        assertEquals(EsdeSyncState.ERROR, evaluate(healthy.copy(pullErrors = 1)))
    }

    @Test fun remoteNeedIgnoresOnlySafeNonContentEntries() {
        assertFalse(EsdeRemoteNeedPolicy.isBlocking(item("gba/gamelist.xml", "FILE_INFO_TYPE_FILE")))
        assertFalse(EsdeRemoteNeedPolicy.isBlocking(item("PSP/SYSTEM/CACHE", "FILE_INFO_TYPE_DIRECTORY")))
        assertTrue(EsdeRemoteNeedPolicy.isBlocking(item("gb/.esde-sync/Batman.zip.esde.json", "FILE_INFO_TYPE_FILE")))
        assertTrue(EsdeRemoteNeedPolicy.isBlocking(item("SAVEDATA/game/save.dat", "FILE_INFO_TYPE_FILE")))
    }

    @Test fun remoteNeedRestPayloadReadsFilesArray() {
        val payload = """{"files":[{"name":"gba/gamelist.xml","type":"FILE_INFO_TYPE_FILE","size":42}],"page":1,"perpage":1000}"""
        val need = Gson().fromJson(payload, RemoteNeed::class.java)

        assertEquals(1, need.allItems().size)
        assertEquals("gba/gamelist.xml", need.allItems().single().name)
        assertEquals(42L, need.allItems().single().size)
    }

    @Test fun bootstrapNeverChoosesLocalAuthorityAutomatically() {
        assertEquals(EsdeBootstrapAction.IMPORT_EXISTING, EsdeBootstrapEvaluator.evaluate(false, true))
        assertEquals(EsdeBootstrapAction.REQUIRE_SOURCE_CONFIRMATION, EsdeBootstrapEvaluator.evaluate(false, false))
        assertEquals(EsdeBootstrapAction.START_OBSERVING, EsdeBootstrapEvaluator.evaluate(true, false))
    }

    @Test fun setupEvaluatorReportsTheExactMissingRequirements() {
        val missing = EsdeSetupEvaluator.missing(
            EsdeSetupInput(
                enabled = true,
                esdeDirectorySelected = true,
                gamelistDirectorySelected = true,
                applicationSelected = true,
                primaryDeviceSelected = false,
                gamingFoldersSelected = true,
                metadataSourceReady = false,
            )
        )

        assertEquals(
            setOf(EsdeSetupRequirement.PRIMARY_DEVICE, EsdeSetupRequirement.INITIAL_METADATA_SOURCE),
            missing,
        )
    }

    @Test fun ignoreRuleHonorsSyncthingFirstMatchSemantics() {
        assertEquals(EsdeIgnoreRuleState.MISSING, EsdeIgnoreRules.evaluate(listOf("*.tmp")))
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeIgnoreRules.evaluate(listOf("(?i)gamelist.xml")))
        assertEquals(
            EsdeIgnoreRuleState.CONFLICTING_INCLUDE,
            EsdeIgnoreRules.evaluate(listOf("!**/gamelist.xml", "gamelist.xml")),
        )
        val includes = listOf("!/snes", "!/snes/**", "*", "gamelist.xml")
        assertEquals(EsdeIgnoreRuleState.MISSING, EsdeIgnoreRules.evaluate(includes))
        val corrected = EsdeIgnoreRules.placeIgnoreRuleFirst(includes)
        assertEquals("gamelist.xml", corrected.first())
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeIgnoreRules.evaluate(corrected))
        assertEquals(1, corrected.count { it == "gamelist.xml" })
    }

    private fun evaluate(folder: EsdeFolderHealth, connected: Boolean = true) = EsdeSyncStateEvaluator.evaluate(
        EsdeGateInput(true, true, connected, false, listOf(folder))
    )

    private fun item(name: String, type: String) = RemoteNeedItem().apply {
        this.name = name
        this.type = type
    }
}

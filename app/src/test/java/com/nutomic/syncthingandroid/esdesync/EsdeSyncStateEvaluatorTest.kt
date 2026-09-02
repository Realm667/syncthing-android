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
                romFolderSelected = true,
                sharedStateFolderReady = true,
                metadataSourceReady = false,
            )
        )

        assertEquals(
            setOf(EsdeSetupRequirement.PRIMARY_DEVICE, EsdeSetupRequirement.INITIAL_METADATA_SOURCE),
            missing,
        )
    }

    @Test fun firstSetupUnlocksLiveTargetsAndTreatsReceiverAndSourceSafely() {
        assertFalse(EsdeFirstSetupPolicy.canChooseSyncTargets(false))
        assertTrue(EsdeFirstSetupPolicy.canChooseSyncTargets(true))
        assertTrue(
            EsdeFirstSetupPolicy.canFinish(
                coreComplete = true,
                apiReady = true,
                coordinatorReady = true,
                role = EsdeSyncSettings.ROLE_RECEIVER,
                sourceInitialized = false,
            ),
        )
        assertFalse(
            EsdeFirstSetupPolicy.canFinish(
                coreComplete = true,
                apiReady = true,
                coordinatorReady = true,
                role = EsdeSyncSettings.ROLE_SOURCE,
                sourceInitialized = false,
            ),
        )
        assertTrue(
            EsdeFirstSetupPolicy.canFinish(
                coreComplete = true,
                apiReady = true,
                coordinatorReady = true,
                role = EsdeSyncSettings.ROLE_SOURCE,
                sourceInitialized = true,
            ),
        )
    }

    @Test fun deferredFirstSetupIsNotOpenedAgainUntilTheUserRequestsIt() {
        assertTrue(EsdeFirstSetupPolicy.shouldOpenAutomatically(true, false, false))
        assertFalse(EsdeFirstSetupPolicy.shouldOpenAutomatically(true, false, true))
        assertFalse(EsdeFirstSetupPolicy.shouldOpenAutomatically(true, true, false))
        assertFalse(EsdeFirstSetupPolicy.shouldOpenAutomatically(false, false, false))
    }

    @Test fun doneEndsInIdleWithoutStartingAnotherSynchronization() {
        assertEquals(
            EsdeSyncState.IDLE,
            EsdeSafeLaunchCompletionPolicy.afterDone(EsdeSyncState.SAFE_TO_SWITCH),
        )
        assertEquals(
            EsdeSyncState.ERROR,
            EsdeSafeLaunchCompletionPolicy.afterDone(EsdeSyncState.ERROR),
        )
    }

    @Test fun processStopWaitsForConfirmedExitAndIsBounded() {
        var runningChecks = 0
        var stopRequests = 0
        var waits = 0
        assertTrue(
            EsdeProcessStopPolicy.stop(
                attempts = 5,
                intervalMs = 1,
                requestStop = { stopRequests++ },
                isRunning = { ++runningChecks < 3 },
                wait = { waits++ },
            ),
        )
        assertEquals(3, stopRequests)
        assertEquals(2, waits)

        assertFalse(
            EsdeProcessStopPolicy.stop(
                attempts = 2,
                intervalMs = 1,
                requestStop = { },
                isRunning = { true },
                wait = { },
            ),
        )
    }

    @Test fun ignoreRuleHonorsSyncthingFirstMatchSemantics() {
        assertEquals(EsdeIgnoreRuleState.MISSING, EsdeRomIgnoreRules.evaluate(listOf("*.tmp")))
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeRomIgnoreRules.evaluate(listOf("(?i)gamelist.xml")))
        assertEquals(
            EsdeIgnoreRuleState.CONFLICTING_INCLUDE,
            EsdeRomIgnoreRules.evaluate(listOf("!**/gamelist.xml", "gamelist.xml")),
        )
        val includes = listOf("!/snes", "!/snes/**", "*", "gamelist.xml")
        assertEquals(EsdeIgnoreRuleState.MISSING, EsdeRomIgnoreRules.evaluate(includes))
        val corrected = EsdeRomIgnoreRules.placeIgnoreRuleFirst(includes)
        assertEquals("gamelist.xml", corrected.first())
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeRomIgnoreRules.evaluate(corrected))
        assertEquals(1, corrected.count { it == "gamelist.xml" })
        assertEquals(corrected, EsdeRomIgnoreRules.placeIgnoreRuleFirst(corrected))
        assertFalse(corrected.any { it.contains(".esde-sync-global") })
    }

    @Test fun sharedStateIgnoreRulesAreIndependentFromRomRules() {
        val existing = listOf("!/collections/*.xcc", "*", "gamelist.xml")
        val corrected = EsdeSharedStateIgnoreRules.placeRulesFirst(existing)
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeSharedStateIgnoreRules.evaluate(corrected))
        assertEquals(EsdeSharedStateIgnoreRules.REQUIRED_RULES, corrected.take(2))
        assertEquals("gamelist.xml", corrected.last())
        assertEquals(
            EsdeIgnoreRuleState.MISSING,
            EsdeSharedStateIgnoreRules.evaluate(listOf("*", "!/.esde-sync-global", "!/.esde-sync-global/**")),
        )
    }

    private fun evaluate(folder: EsdeFolderHealth, connected: Boolean = true) = EsdeSyncStateEvaluator.evaluate(
        EsdeGateInput(true, true, connected, false, listOf(folder))
    )

    private fun item(name: String, type: String) = RemoteNeedItem().apply {
        this.name = name
        this.type = type
    }

}

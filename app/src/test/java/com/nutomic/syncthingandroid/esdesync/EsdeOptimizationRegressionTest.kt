package com.nutomic.syncthingandroid.esdesync

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EsdeOptimizationRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedExportIsRetriedBeforePublishingOrSyncing() {
        var step = EsdeSessionStep.CLOSE_ESDE
        var fail = true
        var synced = false
        val performed = mutableListOf<EsdeSessionStep>()
        val errors = mutableListOf<String>()
        fun workflow() = EsdeSessionWorkflow(
            perform = { current, callback ->
                performed += current
                callback(if (current == EsdeSessionStep.EXPORT_METADATA && fail) Result.failure(Exception("disk full")) else Result.success(Unit))
            },
            checkpoint = { next, callback -> step = next; callback() },
            failed = { errors += it }, readyToSync = { synced = true },
        )
        workflow().resume(step)
        assertEquals(EsdeSessionStep.EXPORT_METADATA, step)
        assertFalse(synced)
        assertTrue(errors.single().contains("disk full"))
        fail = false
        workflow().resume(step)
        assertEquals(listOf(EsdeSessionStep.CLOSE_ESDE, EsdeSessionStep.EXPORT_METADATA,
            EsdeSessionStep.EXPORT_METADATA, EsdeSessionStep.PUBLISH_SHARED_STATE), performed)
        assertEquals(EsdeSessionStep.SYNC_FILES, step)
        assertTrue(synced)
    }

    @Test fun syncCannotRunUntilCheckpointWasPersisted() {
        var continuation: (() -> Unit)? = null
        var sync = false
        EsdeSessionWorkflow({ _, done -> done(Result.success(Unit)) },
            { _, done -> continuation = done }, { fail(it) }, { sync = true })
            .resume(EsdeSessionStep.PUBLISH_SHARED_STATE)
        assertFalse(sync)
        continuation!!()
        assertTrue(sync)
    }

    @Test fun everyFailedFinalizationStepBlocksCompletion() {
        for (failure in EsdeSessionStep.entries.filter { it != EsdeSessionStep.SYNC_FILES }) {
            var reached = false
            var error = ""
            EsdeSessionWorkflow({ step, done -> done(if (step == failure) Result.failure(Exception("injected")) else Result.success(Unit)) },
                { _, done -> done() }, { error = it }, { reached = true }).resume(EsdeSessionStep.CLOSE_ESDE)
            assertFalse(reached)
            assertTrue(error.contains("injected"))
        }
    }

    @Test fun duplicateCallbacksCannotFinalizeTwice() {
        var finalized = 0
        EsdeSessionWorkflow({ _, done -> done(Result.success(Unit)); done(Result.success(Unit)) },
            { _, done -> done() }, { fail(it) }, { finalized++ }).resume(EsdeSessionStep.CLOSE_ESDE)
        assertEquals(1, finalized)
    }

    @Test fun failedCheckpointOrThrownOperationCannotStartSynchronization() {
        var errors = 0
        EsdeSessionWorkflow({ _, _ -> error("disk full") }, { _, _ -> fail("checkpoint should not run") },
            { errors++ }, { fail("sync should not run") }).resume(EsdeSessionStep.EXPORT_METADATA)
        EsdeSessionWorkflow({ _, done -> done(Result.success(Unit)) }, { _, _ -> error("disk full") },
            { errors++ }, { fail("sync should not run") }).resume(EsdeSessionStep.PUBLISH_SHARED_STATE)
        assertEquals(2, errors)
    }

    @Test fun ignoreWriteWaitsForAcknowledgementAndReadback() {
        var acknowledgement: (() -> Unit)? = null
        var readback: ((List<String>) -> Unit)? = null
        var success: Boolean? = null
        EsdeVerifiedIgnoreUpdate({ _, ok, _ -> acknowledgement = ok }, { ok, _ -> readback = ok })
            .apply(listOf("gamelist.xml"), { it.firstOrNull() == "gamelist.xml" }, { success = it })
        assertNull(readback)
        assertNull(success)
        acknowledgement!!()
        assertNull(success)
        readback!!(listOf("gamelist.xml"))
        assertEquals(true, success)
    }

    @Test fun ignoreWriteAndVerificationFailuresRemainFailures() {
        val modes = listOf("post", "get", "mismatch")
        for (mode in modes) {
            var success: Boolean? = null
            EsdeVerifiedIgnoreUpdate({ _, ok, fail -> if (mode == "post") fail() else ok() },
                { ok, fail -> if (mode == "get") fail() else ok(listOf("!/snes/**")) })
                .apply(listOf("gamelist.xml"), { it.firstOrNull() == "gamelist.xml" }, { success = it })
            assertEquals(false, success)
        }
    }

    @Test fun damagedJournalIsNotMistakenForNoPendingWork() {
        val file = temporary.newFile("journal.json")
        file.writeText("{broken")
        assertThrows(Exception::class.java) { EsdeOfflineJournal(file).load() }
        file.writeText("null")
        assertThrows(Exception::class.java) { EsdeOfflineJournal(file).load() }
    }

    @Test fun oldJournalResumesFromCloseAndNewCheckpointSurvivesReload() {
        val file = temporary.newFile("journal.json")
        file.writeText("""{"schemaVersion":1,"sessionId":"legacy","startedAt":1,"folderIds":["roms"],"status":"PENDING"}""")
        val journal = EsdeOfflineJournal(file)
        assertEquals(EsdeSessionStep.CLOSE_ESDE, journal.load()!!.nextStep ?: EsdeSessionStep.CLOSE_ESDE)
        journal.advance(EsdeSessionStep.PUBLISH_SHARED_STATE)
        assertEquals(EsdeSessionStep.PUBLISH_SHARED_STATE, EsdeOfflineJournal(file).load()!!.nextStep)
        assertTrue(File(file.parentFile, "journal.json.backup").isFile)
    }

    @Test fun newPlaySessionAlwaysResetsFinalizationCheckpoint() {
        val journal = EsdeOfflineJournal(File(temporary.root, "journal.json"))
        journal.begin("first", setOf("roms"))
        journal.advance(EsdeSessionStep.SYNC_FILES)
        journal.begin("second", setOf("roms", "settings"))
        assertEquals(EsdeSessionStep.CLOSE_ESDE, journal.load()!!.nextStep)
    }

    @Test fun identicalSharedSnapshotsKeepTheirModificationTime() {
        val store = EsdeSharedSnapshotStore(temporary.root)
        val snapshot = EsdeSharedSnapshot("local", "shared", withheld = true)
        store.save("settings", "Theme", snapshot)
        val file = temporary.root.walkTopDown().single { it.isFile }
        assertTrue(file.setLastModified(1_000_000))
        store.save("settings", "Theme", snapshot)
        assertEquals(1_000_000L, file.lastModified())
    }

    @Test fun corruptSharedSnapshotRequiresRecovery() {
        val store = EsdeSharedSnapshotStore(temporary.root)
        store.save("settings", "Theme", EsdeSharedSnapshot("a", "b"))
        temporary.root.walkTopDown().single { it.isFile }.writeText("broken")
        assertThrows(Exception::class.java) { store.load("settings", "Theme") }
    }

    @Test fun missingLocalGamelistDoesNotReportSuccess() {
        val system = temporary.newFolder("snes")
        val bridge = EsdeMetadataBridge(EsdeGamelistParser(), EsdeSidecarStore(),
            EsdeSnapshotStore(temporary.newFolder("snapshots")), EsdeBackupManager(temporary.newFolder("backups")))
        assertFalse(bridge.exportSystem(system).successful)
        assertTrue(bridge.importSystem(system).invalid > 0)
    }

    @Test fun negativeCountsAndImpossibleDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EsdeMetadataValidation.validate(EsdeGameState(game = "./a.sfc", playcount = -1)) }
        assertThrows(IllegalArgumentException::class.java) { EsdeMetadataValidation.validate(EsdeGameState(game = "./a.sfc", playtime = -1)) }
        assertThrows(IllegalArgumentException::class.java) { EsdeMetadataValidation.validate(EsdeGameState(game = "./a.sfc", lastplayed = "20260230T120000")) }
        EsdeMetadataValidation.validate(EsdeGameState(game = "./a.sfc", lastplayed = "20260228T120000"))
    }

    @Test fun sidecarReaderRejectsCoercedBooleansAndFractionalCounters() {
        val system = temporary.newFolder("snes")
        val file = EsdePathPolicy.sidecarFile(system, "./a.sfc")
        file.parentFile!!.mkdirs()
        for (field in listOf("\"favorite\":\"true\"", "\"playcount\":1.5", "\"rating\":\"0.5\"")) {
            file.writeText("""{"schemaVersion":1,"game":"./a.sfc",$field}""")
            assertThrows(Exception::class.java) { EsdeSidecarStore().read(file) }
        }
    }

    @Test fun streamingParserAcceptsEsdeSiblingRootsAndPreservesUtf8() {
        val file = temporary.newFile("gamelist.xml")
        file.writeText("""<?xml version="1.0" encoding="UTF-8"?><alternativeEmulator>default</alternativeEmulator><gameList><game><path>./Über.sfc</path><favorite>true</favorite><desc>${"ignored ".repeat(20_000)}</desc></game></gameList>""")
        assertEquals(true, EsdeGamelistParser().parse(file)["./Über.sfc"]?.favorite)
        EsdeGamelistParser().apply(file, mapOf("./Über.sfc" to EsdeMetadata(playcount = 4)))
        assertEquals(4L, EsdeGamelistParser().parse(file)["./Über.sfc"]?.playcount)
        assertTrue(file.readText().contains("ignored"))
    }

    @Test fun streamingParserRejectsDoctypeAndUnknownRoots() {
        val file = temporary.newFile("gamelist.xml")
        for (xml in listOf("<!DOCTYPE gameList><gameList/>", "<unknown/><gameList/>", "<gameList/><gameList/>")) {
            file.writeText(xml)
            assertThrows(Exception::class.java) { EsdeGamelistParser().parse(file) }
            assertThrows(Exception::class.java) { EsdeGamelistParser().apply(file, emptyMap()) }
        }
    }

    @Test fun singleSettingFragmentCanReceiveAdditionalSettingsWithoutNesting() {
        val file = temporary.newFile("settings.xml")
        file.writeText("<bool name=\"DisplayClock\" value=\"true\" />")
        EsdeSettingsEditor().apply(file, mapOf("StartupSystem" to EsdeSettingsEditor.XmlSetting("string", "snes")))
        assertTrue(file.readText().contains("StartupSystem"))
        val values = EsdeSettingsEditor().read(file, setOf("DisplayClock", "StartupSystem"))
        assertEquals("snes", values["StartupSystem"]?.value)
        assertEquals("true", values["DisplayClock"]?.value)
        assertFalse(file.readText().contains("</bool>"))
    }

    @Test fun strictSharedIgnoreRulesAreIdempotentAndPrecedeRawIncludes() {
        val corrected = EsdeSharedStateIgnoreRules.placeRulesFirst(listOf("!/settings/**", "!/collections/**", "*"))
        assertEquals(listOf("!/.esde-sync-global", "!/.esde-sync-global/**", "*"), corrected.take(3))
        assertEquals(corrected, EsdeSharedStateIgnoreRules.placeRulesFirst(corrected))
        assertEquals(EsdeIgnoreRuleState.ACTIVE, EsdeSharedStateIgnoreRules.evaluate(corrected))
        assertEquals(EsdeIgnoreRuleState.MISSING, EsdeSharedStateIgnoreRules.evaluate(corrected.take(2)))
    }

    @Test fun conflictInventoryCachesByIndexRevisionAndInvalidates() {
        val root = temporary.newFolder("quoted ' folder")
        val inventory = EsdeConflictInventory { 100 }
        assertTrue(inventory.scan(root.path, "1").isEmpty())
        val conflict = File(root, "a.sync-conflict-20261007-120000-ABCDEFG.xml").apply { writeText("x") }
        assertTrue(inventory.scan(root.path, "1").isEmpty())
        assertEquals(listOf(conflict.name), inventory.scan(root.path, "2").toList())
        conflict.delete()
        inventory.invalidate(root.path)
        assertTrue(inventory.scan(root.path, "2").isEmpty())
    }

    @Test fun inaccessibleConflictRootCannotBeAcceptedAsEmpty() {
        assertThrows(Exception::class.java) { EsdeConflictInventory().scan(File(temporary.root, "missing").path, "1") }
    }

    @Test fun conflictScanExcludesVersionHistory() {
        val root = temporary.newFolder("roms")
        File(File(root, ".stversions").apply { mkdir() }, "a.sync-conflict-20261007-120000-ABCDEFG.xml").writeText("old")
        assertTrue(EsdeConflictInventory().scan(root.path, "1").isEmpty())
    }

    @Test fun folderRolesMustNotOverlapOrEscape() {
        val rom = temporary.newFolder("rom")
        val esde = temporary.newFolder("esde")
        val nested = File(rom, "settings").apply { mkdir() }
        assertThrows(IllegalArgumentException::class.java) { EsdeFolderConfiguration.validate(rom, rom, esde, nested) }
        assertThrows(IllegalArgumentException::class.java) { EsdeFolderConfiguration.validate(rom, esde, esde, null) }
        EsdeFolderConfiguration.validate(rom, rom, esde, temporary.newFolder("shared"))
    }

    @Test fun watchdogIsProgressSensitiveAndNotASessionDeadline() {
        val watchdog = EsdeProgressWatchdog(100)
        assertFalse(watchdog.stalled(10, 1000))
        assertFalse(watchdog.stalled(9, 1099))
        assertFalse(watchdog.stalled(9, 1150))
        assertTrue(watchdog.stalled(9, 1200))
        assertFalse(watchdog.stalled(8, 1300))
    }

    @Test fun transferRateUsesElapsedTimeAndSurvivesCounterReset() {
        val meter = EsdeTransferMeter()
        assertNull(meter.sample(1000, 2000, 1000))
        assertEquals(EsdeTransferMeter.Rate(1000.0, 2000.0), meter.sample(2500, 5000, 2500))
        assertNull(meter.sample(0, 0, 3000))
        assertEquals(EsdeTransferMeter.Rate(0.0, 0.0), meter.sample(0, 0, 4000))
        meter.reset()
        assertNull(meter.sample(100, 200, 5000))
    }
}

package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeSharedCollectionsManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importPreservesOtherCollectionsAndExistingEnabledEntries() {
        val gamelists = temporary.newFolder("roms")
        val esde = temporary.newFolder("ES-DE")
        val shared = File(File(gamelists, EsdeGlobalLayout.DIRECTORY), "collections").apply { mkdirs() }
        File(shared, "2 Player.xcc").writeText(xml("2 Player", "players", "2"))
        val local = File(esde, "collections").apply { mkdirs() }
        File(local, "Local Only.xcc").writeText(xml("Local Only", "favorites", "TRUE"))
        val settings = File(File(esde, "settings").apply { mkdirs() }, "es_settings.xml").apply {
            writeText("<string name=\"CollectionSystemsCustom\" value=\"Local Only\" />")
        }
        val manager = manager(gamelists, esde)
        val result = manager.importSelected(setOf("2 Player"), settings)

        assertTrue(result.successful)
        assertTrue(File(local, "2 Player.xcc").isFile)
        assertTrue(File(local, "Local Only.xcc").isFile)
        assertEquals(
            "Local Only,2 Player",
            EsdeSettingsEditor().read(settings, setOf("CollectionSystemsCustom"))["CollectionSystemsCustom"]?.value,
        )
    }

    @Test fun firstTimeMismatchIsAConflictAndNeverOverwrites() {
        val gamelists = temporary.newFolder("roms-conflict")
        val esde = temporary.newFolder("ES-DE-conflict")
        val shared = File(File(gamelists, EsdeGlobalLayout.DIRECTORY), "collections").apply { mkdirs() }
        File(shared, "Top.xcc").writeText(xml("Top", "ratings", "5 STARS"))
        val local = File(esde, "collections").apply { mkdirs() }
        val localFile = File(local, "Top.xcc").apply { writeText(xml("Top", "ratings", "4 STARS")) }
        val settings = File(File(esde, "settings").apply { mkdirs() }, "es_settings.xml").apply { writeText("<settings />") }

        val result = manager(gamelists, esde).importSelected(setOf("Top"), settings)
        assertEquals(listOf("Top"), result.conflicts)
        assertTrue(localFile.readText().contains("4 STARS"))
    }

    private fun manager(gamelists: File, esde: File) = EsdeSharedCollectionsManager(
        gamelists, esde,
        EsdeSharedSnapshotStore(temporary.newFolder("snapshots-${System.nanoTime()}")),
        EsdePrivateFileBackup(temporary.newFolder("backups-${System.nanoTime()}")),
    )

    private fun xml(name: String, key: String, value: String) =
        "<?xml version=\"1.0\"?><filter name=\"$name\"><$key>$value</$key></filter>"
}

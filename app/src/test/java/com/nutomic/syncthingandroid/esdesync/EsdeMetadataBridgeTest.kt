package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeMetadataBridgeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportWritesOnlyChangedGamesAndImportDoesNotFeedBack() {
        val system = temporary.newFolder("snes")
        val gamelist = File(system, "gamelist.xml")
        gamelist.writeText(xml(false, 13))
        val bridge = bridge()
        assertEquals(2, bridge.exportSystem(system, full = true).sidecarsWritten)
        assertEquals(0, bridge.exportSystem(system).sidecarsWritten)

        gamelist.writeText(xml(true, 13))
        assertEquals(1, bridge.exportSystem(system).sidecarsWritten)
        assertEquals(0, bridge.exportSystem(system).sidecarsWritten)

        EsdeSidecarStore().write(system, "./Chrono Trigger.sfc", EsdeMetadata(playcount = 14))
        assertEquals(1, bridge.importSystem(system).changedGames)
        assertEquals(0, bridge.exportSystem(system).sidecarsWritten)
    }

    @Test fun eachSupportedFieldTriggersExactlyOneSidecarWrite() {
        val system = temporary.newFolder("fields")
        val gamelist = File(system, "gamelist.xml")
        val bridge = bridge()
        var value = EsdeMetadata(false, false, 0, 0, "", "DEFAULT")
        gamelist.writeText(singleGameXml(value))
        assertEquals(1, bridge.exportSystem(system, full = true).sidecarsWritten)
        val changes = listOf(
            value.copy(favorite = true),
            value.copy(favorite = true, completed = true),
            value.copy(favorite = true, completed = true, playcount = 1),
            value.copy(favorite = true, completed = true, playcount = 1, playtime = 9_999_999),
            value.copy(favorite = true, completed = true, playcount = 1, playtime = 9_999_999, lastplayed = "20260831T120000"),
            value.copy(favorite = true, completed = true, playcount = 1, playtime = 9_999_999, lastplayed = "20260831T120000", altemulator = "RETROARCH:MGBA"),
        )
        changes.forEach { next ->
            gamelist.writeText(singleGameXml(next))
            assertEquals(1, bridge.exportSystem(system).sidecarsWritten)
            assertEquals(0, bridge.exportSystem(system).sidecarsWritten)
            value = next
        }
    }

    private fun bridge(): EsdeMetadataBridge = EsdeMetadataBridge(
        EsdeGamelistParser(),
        EsdeSidecarStore(),
        EsdeSnapshotStore(temporary.newFolder("snapshots-${System.nanoTime()}")),
        EsdeBackupManager(temporary.newFolder("backups-${System.nanoTime()}")),
    )

    private fun xml(favorite: Boolean, otherPlaycount: Long) = """
        <gameList>
          <game><path>./Chrono Trigger.sfc</path><favorite>$favorite</favorite><playcount>13</playcount></game>
          <game><path>./Super Mario World.sfc</path><favorite>false</favorite><playcount>$otherPlaycount</playcount></game>
        </gameList>
    """.trimIndent()

    private fun singleGameXml(value: EsdeMetadata) = """
        <gameList><game><path>./Game.sfc</path><favorite>${value.favorite}</favorite>
        <completed>${value.completed}</completed><playcount>${value.playcount}</playcount>
        <playtime>${value.playtime}</playtime><lastplayed>${value.lastplayed}</lastplayed>
        <altemulator>${value.altemulator}</altemulator></game></gameList>
    """.trimIndent()
}

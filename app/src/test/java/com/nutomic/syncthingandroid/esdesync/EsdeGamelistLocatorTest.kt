package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeGamelistLocatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun discoversGamelistsDirectlyBelowRomRoot() {
        val roms = temporary.newFolder("roms")
        val snes = File(roms, "snes").apply { mkdirs() }
        File(snes, "gamelist.xml").writeText("<gameList/>")
        File(roms, "gba").mkdirs()

        val locator = EsdeGamelistLocator(roms)

        assertEquals(listOf(snes.canonicalFile), locator.systemDirectories().map { it.canonicalFile })
        assertTrue(locator.contains(File(snes, ".esde-sync/Game.sfc.esde.json")))
        assertFalse(locator.contains(temporary.newFile("outside.esde.json")))
    }

    @Test fun keepsSystemDiscoverableWhenOnlyReceivedSidecarsExist() {
        val root = temporary.newFolder("gamelists")
        val system = File(root, "snes/.esde-sync").apply { mkdirs() }.parentFile

        assertEquals(listOf(system.canonicalFile), EsdeGamelistLocator(root).systemDirectories().map { it.canonicalFile })
    }
}

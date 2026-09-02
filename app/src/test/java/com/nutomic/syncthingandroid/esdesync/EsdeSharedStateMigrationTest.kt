package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeSharedStateMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun migrationCopiesValidatedLegacyDataAndNeverOverwritesConflict() {
        val legacy = temporary.newFolder("roms")
        val destination = temporary.newFolder("shared")
        val legacyGlobal = File(legacy, EsdeGlobalLayout.DIRECTORY)
        val collections = File(legacyGlobal, "collections").apply { mkdirs() }
        File(collections, "Top.xcc").writeText("<filter name=\"Top\"><rating>5 STARS</rating></filter>")
        val settings = File(legacyGlobal, "settings").apply { mkdirs() }
        File(settings, EsdeGlobalLayout.SETTINGS_FILE).writeText("""{"schemaVersion":1,"settings":{}}""")
        val migration = EsdeSharedStateMigration(
            EsdePrivateFileBackup(temporary.newFolder("backups")),
        )

        val first = migration.migrate(legacy, destination)
        assertTrue(first.successful)
        assertEquals(2, first.copied)

        File(File(File(destination, EsdeGlobalLayout.DIRECTORY), "collections"), "Top.xcc")
            .writeText("<filter name=\"Top\"><rating>4 STARS</rating></filter>")
        val second = migration.migrate(legacy, destination)
        assertEquals(listOf("collections${File.separator}Top.xcc"), second.conflicts)
    }

    @Test fun missingLegacyDataIsARecoverableNoOp() {
        val migration = EsdeSharedStateMigration(
            EsdePrivateFileBackup(temporary.newFolder("backups-empty")),
        )
        val result = migration.migrate(temporary.newFolder("empty"), temporary.newFolder("destination"))
        assertTrue(result.successful)
        assertEquals(1, result.skipped)
    }
}

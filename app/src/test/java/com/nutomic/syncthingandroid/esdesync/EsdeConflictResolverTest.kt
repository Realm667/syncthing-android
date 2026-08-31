package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeConflictResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun keepCurrentArchivesAndRemovesOnlyTheConflictCopy() {
        val root = temporary.newFolder("folder")
        val backups = temporary.newFolder("backups")
        val current = File(root, "save.dat").apply { writeText("current") }
        val conflict = File(root, "save.sync-conflict-20260831-191230-BAUQQTP.dat").apply { writeText("other") }
        val resolver = EsdeConflictResolver(backups)

        resolver.resolve(root, conflict.name, EsdeConflictResolution.KEEP_CURRENT)

        assertEquals("current", current.readText())
        assertFalse(conflict.exists())
        assertTrue(File(backups, "conflicts").listFiles().orEmpty().any { it.readText() == "other" })
    }

    @Test fun useConflictArchivesBothAndReplacesAtomically() {
        val root = temporary.newFolder("folder-replace")
        val backups = temporary.newFolder("backups-replace")
        val current = File(root, "save.dat").apply { writeText("current") }
        val conflict = File(root, "save.sync-conflict-20260831-191230-BAUQQTP.dat").apply { writeText("chosen") }

        EsdeConflictResolver(backups).resolve(root, conflict.name, EsdeConflictResolution.USE_CONFLICT_COPY)

        assertEquals("chosen", current.readText())
        assertFalse(conflict.exists())
        assertEquals(setOf("current", "chosen"), File(backups, "conflicts").listFiles().orEmpty().map { it.readText() }.toSet())
    }

    @Test fun gamelistCanNeverBeReplacedFromAConflictCopy() {
        val root = temporary.newFolder("folder-gamelist")
        val conflict = File(root, "gamelist.sync-conflict-20260831-191230-BAUQQTP.xml").apply { writeText("<gameList/>") }
        val resolver = EsdeConflictResolver(temporary.newFolder("backups-gamelist"))

        assertFalse(resolver.details(root, conflict.name).canUseConflictCopy)
        runCatching { resolver.resolve(root, conflict.name, EsdeConflictResolution.USE_CONFLICT_COPY) }
            .onSuccess { error("Expected gamelist replacement to be rejected") }
        assertTrue(conflict.exists())
    }

    @Test fun traversalIsRejected() {
        val root = temporary.newFolder("folder-traversal")
        val resolver = EsdeConflictResolver(temporary.newFolder("backups-traversal"))
        runCatching { resolver.details(root, "../escape.sync-conflict-20260831-191230-AAAAAAA.dat") }
            .onSuccess { error("Expected traversal to be rejected") }
    }
}

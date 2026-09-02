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

    @Test fun conflictRefreshDropsOnlyMissingCachedCopies() {
        val root = temporary.newFolder("refresh-stale-conflicts")
        val existing = File(root, "es_settings.sync-conflict-20260902-120000-RG477VV.xml")
            .apply { writeText("<string name=\"Theme\" value=\"art-book-next\"/>") }
        val missing = "es_settings.sync-conflict-20260902-120100-RG476HH.xml"

        val result = EsdeConflictResolver(temporary.newFolder("backups-refresh"))
            .existingConflicts(root, listOf(existing.name, missing))

        assertEquals(listOf(existing.name), result)
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

    @Test fun batchUsesConflictCopiesButAlwaysKeepsLocalGamelist() {
        val root = temporary.newFolder("folder-batch")
        val backups = temporary.newFolder("backups-batch")
        val save = File(root, "save.dat").apply { writeText("old-save") }
        val saveConflict = File(root, "save.sync-conflict-20260831-191230-BAUQQTP.dat")
            .apply { writeText("new-save") }
        val gamelist = File(root, "gamelist.xml").apply { writeText("<gameList><game/></gameList>") }
        val gamelistConflict = File(root, "gamelist.sync-conflict-20260831-191230-BAUQQTP.xml")
            .apply { writeText("<gameList/>") }

        val count = EsdeConflictResolver(backups).resolveAll(
            root,
            listOf(saveConflict.name, gamelistConflict.name),
            EsdeConflictResolution.USE_CONFLICT_COPY,
        )

        assertEquals(2, count)
        assertEquals("new-save", save.readText())
        assertEquals("<gameList><game/></gameList>", gamelist.readText())
        assertFalse(saveConflict.exists())
        assertFalse(gamelistConflict.exists())
        val backupContents = backups.walkTopDown().filter(File::isFile).map(File::readText).toSet()
        assertEquals(
            setOf("old-save", "new-save", "<gameList><game/></gameList>", "<gameList/>"),
            backupContents,
        )
    }

    @Test fun batchPreflightFailureLeavesEveryConflictUntouched() {
        val root = temporary.newFolder("folder-batch-preflight")
        val current = File(root, "first.dat").apply { writeText("current") }
        val first = File(root, "first.sync-conflict-20260831-191230-BAUQQTP.dat").apply { writeText("first") }
        val second = File(root, "missing.sync-conflict-20260831-191231-BAUQQTP.dat").apply { writeText("second") }
        val resolver = EsdeConflictResolver(temporary.newFolder("backups-batch-preflight"))

        runCatching {
            resolver.resolveAll(root, listOf(first.name, second.name), EsdeConflictResolution.KEEP_CURRENT)
        }.onSuccess { error("Expected batch preflight to reject a missing current file") }

        assertEquals("current", current.readText())
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test fun traversalIsRejected() {
        val root = temporary.newFolder("folder-traversal")
        val resolver = EsdeConflictResolver(temporary.newFolder("backups-traversal"))
        runCatching { resolver.details(root, "../escape.sync-conflict-20260831-191230-AAAAAAA.dat") }
            .onSuccess { error("Expected traversal to be rejected") }
    }

    @Test fun conflictRefreshNeverTreatsTraversalAsResolved() {
        val root = temporary.newFolder("refresh-traversal")
        val resolver = EsdeConflictResolver(temporary.newFolder("backups-refresh-traversal"))
        runCatching {
            resolver.existingConflicts(root, listOf("../escape.sync-conflict-20260902-120200-AAAAAAA.dat"))
        }.onSuccess { error("Expected conflict refresh traversal to be rejected") }
    }
}

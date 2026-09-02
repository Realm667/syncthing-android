package com.nutomic.syncthingandroid.esdesync

import com.nutomic.syncthingandroid.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EsdeFolderRoleMigrationTest {
    @Test fun uniqueLegacyNameMigratesToStableId() {
        assertEquals("rom-id", EsdeFolderRoleMigration.legacyRomFolderId(listOf(folder("rom-id", "Master", "Roms"))))
        assertEquals("flat-id", EsdeFolderRoleMigration.legacyRomFolderId(listOf(folder("flat-id", "", "Master / Roms"))))
    }

    @Test fun customAndAmbiguousNamesRequireExplicitSelection() {
        assertNull(EsdeFolderRoleMigration.legacyRomFolderId(listOf(folder("custom", "Master", "Roms (w/Saves)"))))
        assertNull(EsdeFolderRoleMigration.legacyRomFolderId(listOf(
            folder("one", "Master", "Roms"),
            folder("two", "", "Master / Roms"),
        )))
    }

    @Test fun renameAfterMigrationDoesNotMatterBecauseTheIdIsPersisted() {
        val selectedId = EsdeFolderRoleMigration.legacyRomFolderId(listOf(folder("stable", "Master", "Roms")))
        val renamed = folder("stable", "Anything", "Games and Saves")
        assertEquals(renamed.id, selectedId)
    }

    private fun folder(id: String, group: String, label: String) = Folder().apply {
        this.id = id
        this.group = group
        this.label = label
    }
}

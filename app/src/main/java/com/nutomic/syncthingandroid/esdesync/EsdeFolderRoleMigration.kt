package com.nutomic.syncthingandroid.esdesync

import com.nutomic.syncthingandroid.model.Folder

/** One-time compatibility suggestion only; persisted folder IDs remain authoritative. */
object EsdeFolderRoleMigration {
    fun legacyRomFolderId(folders: Collection<Folder>): String? = folders
        .filter(::wasLegacyMasterRomsFolder)
        .mapNotNull { it.id?.takeIf(String::isNotBlank) }
        .distinct()
        .singleOrNull()

    private fun wasLegacyMasterRomsFolder(folder: Folder): Boolean {
        val group = folder.group.trim()
        val label = folder.label.trim()
        return (group.equals("Master", ignoreCase = true) && label.equals("Roms", ignoreCase = true)) ||
            (group.isBlank() && label.equals("Master / Roms", ignoreCase = true))
    }
}

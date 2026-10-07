package com.nutomic.syncthingandroid.esdesync

import java.io.File

object EsdeFolderConfiguration {
    fun validate(rom: File, gamelists: File, esde: File, shared: File?) {
        listOfNotNull(rom, gamelists, esde, shared).forEach {
            require(it.isDirectory && it.canRead() && it.canWrite()) { "Directory is unavailable or not writable: ${it.path}" }
        }
        require(contains(rom, gamelists)) { "The ROM sync folder does not contain the selected gamelist root" }
        if (shared != null) require(!contains(rom, shared) && !contains(shared, rom)) {
            "ROMs and Settings & Collections must use separate, non-overlapping sync folders"
        }
    }

    private fun contains(root: File, child: File): Boolean {
        val base = root.canonicalFile
        val target = child.canonicalFile
        return base == target || target.path.startsWith(base.path.trimEnd(File.separatorChar) + File.separator)
    }
}

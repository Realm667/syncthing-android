package com.nutomic.syncthingandroid.esdesync

import java.io.File

class EsdeGamelistLocator(private val root: File) {
    fun systemDirectories(): List<File> = root
        .listFiles { file -> file.isDirectory &&
            (File(file, EsdeMetadataBridge.GAMELIST).isFile ||
                File(file, EsdeSidecarStore.SIDECAR_DIRECTORY).isDirectory) }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun contains(file: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        file.canonicalPath.startsWith(canonicalRoot)
    }.getOrDefault(false)
}

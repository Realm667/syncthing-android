package com.nutomic.syncthingandroid.esdesync

import java.io.File

object EsdePathPolicy {
    private val drivePrefix = Regex("^[A-Za-z]:")

    fun normalizeGamePath(raw: String): String {
        require(raw.isNotBlank()) { "Empty ES-DE game path" }
        require(raw.length <= MAX_PATH_LENGTH) { "ES-DE game path is too long" }
        require('\u0000' !in raw) { "NUL in ES-DE game path" }
        val slashed = raw.replace('\\', '/')
        require(!slashed.startsWith('/')) { "Absolute ES-DE game path" }
        require(!drivePrefix.containsMatchIn(slashed)) { "Drive-qualified ES-DE game path" }
        val withoutPrefix = slashed.removePrefix("./")
        val segments = withoutPrefix.split('/')
        require(segments.isNotEmpty() && segments.none {
            it.isBlank() || it == "." || it == ".." || it.length > MAX_SEGMENT_LENGTH
        }) {
            "Unsafe ES-DE game path"
        }
        return "./${segments.joinToString("/")}"
    }

    fun sidecarFile(systemDirectory: File, rawGamePath: String): File {
        val normalized = normalizeGamePath(rawGamePath).removePrefix("./")
        val sidecarRoot = File(systemDirectory, EsdeSidecarStore.SIDECAR_DIRECTORY)
        val result = File(sidecarRoot, "$normalized${EsdeSidecarStore.SIDECAR_SUFFIX}")
        val rootPath = sidecarRoot.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(result.canonicalPath.startsWith(rootPath)) { "Sidecar escaped its system root" }
        return result
    }

    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_SEGMENT_LENGTH = 255
}

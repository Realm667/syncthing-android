package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets

enum class EsdeConflictResolution { KEEP_CURRENT, USE_CONFLICT_COPY }

data class EsdeConflictDetails(
    val relativePath: String,
    val originalRelativePath: String,
    val deviceShortId: String,
    val timestamp: String,
    val conflictSize: Long,
    val currentSize: Long?,
    val canUseConflictCopy: Boolean,
)

internal class EsdeConflictResolver(
    private val backupRoot: File,
    private val gson: Gson = Gson(),
) {
    /**
     * Revalidates paths retained by Syncthing's asynchronous conflict cache. Missing conflict
     * copies are stale cache entries, not user decisions, and can therefore be forgotten safely.
     * Invalid or escaping paths are rejected by resolveFiles and are never treated as resolved.
     */
    fun existingConflicts(folderRoot: File, relativePaths: List<String>): List<String> {
        val uniquePaths = relativePaths.distinct()
        require(uniquePaths.size <= MAX_BATCH_FILES) { "Too many conflicts in one refresh" }
        return uniquePaths.filter { resolveFiles(folderRoot, it).conflict.isFile }
    }

    fun details(folderRoot: File, relativePath: String): EsdeConflictDetails {
        val files = resolveFiles(folderRoot, relativePath)
        val match = CONFLICT_MARKER.find(files.conflict.name)
            ?: throw IllegalArgumentException("Not a Syncthing conflict file")
        return EsdeConflictDetails(
            relativePath = relativePath.replace('\\', '/'),
            originalRelativePath = files.original.relativeTo(files.root).path.replace('\\', '/'),
            deviceShortId = match.groupValues[3],
            timestamp = "${match.groupValues[1]} ${match.groupValues[2]}",
            conflictSize = files.conflict.length(),
            currentSize = files.original.takeIf(File::isFile)?.length(),
            canUseConflictCopy = !files.original.name.equals("gamelist.xml", ignoreCase = true),
        )
    }

    fun resolve(folderRoot: File, relativePath: String, resolution: EsdeConflictResolution) {
        val files = resolveFiles(folderRoot, relativePath)
        require(resolution != EsdeConflictResolution.USE_CONFLICT_COPY || !files.isGamelist) {
            "gamelist.xml always remains local and cannot be replaced from a conflict copy"
        }
        preflight(listOf(files), resolution, keepGamelistsLocal = false)
        applyResolution(listOf(files), resolution, keepGamelistsLocal = false)
    }

    /**
     * Resolves every listed conflict as one confirmed batch. All paths and replacement payloads
     * are validated and all backups are created before the first synchronized file is changed.
     * gamelist.xml remains local even when conflict copies are selected for the batch.
     */
    fun resolveAll(
        folderRoot: File,
        relativePaths: List<String>,
        resolution: EsdeConflictResolution,
    ): Int {
        val uniquePaths = relativePaths.distinct()
        require(uniquePaths.isNotEmpty()) { "No conflicts selected" }
        require(uniquePaths.size <= MAX_BATCH_FILES) { "Too many conflicts in one batch" }
        val files = uniquePaths.map { resolveFiles(folderRoot, it) }
        preflight(files, resolution, keepGamelistsLocal = true)
        applyResolution(files, resolution, keepGamelistsLocal = true)
        return files.size
    }

    private fun preflight(
        files: List<Files>,
        resolution: EsdeConflictResolution,
        keepGamelistsLocal: Boolean,
    ) {
        files.forEach { item ->
            require(item.conflict.isFile) { "Conflict copy no longer exists: ${item.relativePath}" }
            val keepCurrent = resolution == EsdeConflictResolution.KEEP_CURRENT ||
                (keepGamelistsLocal && item.isGamelist)
            require(!keepCurrent || item.original.isFile) {
                "Current file no longer exists: ${item.originalRelativePath}"
            }
            if (!keepCurrent) validateReplacement(item.conflict, item.original)
        }
    }

    private fun applyResolution(
        files: List<Files>,
        resolution: EsdeConflictResolution,
        keepGamelistsLocal: Boolean,
    ) {
        val backups = EsdePrivateFileBackup(backupRoot)
        files.forEach { item ->
            // Preserve the established single-conflict backup location. Batch resolutions use
            // path-derived buckets so equal filenames from different folders cannot collide.
            val category = if (files.size == 1 && !keepGamelistsLocal) {
                "conflicts"
            } else {
                "conflicts-${EsdeHashes.text(item.originalRelativePath).take(12)}"
            }
            backups.create(category, item.conflict)
            item.original.takeIf(File::isFile)?.let { backups.create(category, it) }
        }

        files.forEach { item ->
            val useConflict = resolution == EsdeConflictResolution.USE_CONFLICT_COPY &&
                !(keepGamelistsLocal && item.isGamelist)
            if (useConflict) {
                AtomicFileWriter.write(item.original) { output ->
                    item.conflict.inputStream().use { it.copyTo(output) }
                }
            }
            check(item.conflict.delete()) { "Could not remove resolved conflict: ${item.relativePath}" }
        }
    }

    private fun validateReplacement(candidate: File, original: File) {
        when {
            original.name.endsWith(EsdeSidecarStore.SIDECAR_SUFFIX, ignoreCase = true) ->
                validateSidecarCandidate(candidate, original)
            original.name.equals(EsdeGlobalLayout.SETTINGS_FILE, ignoreCase = true) ->
                validateSharedSettings(candidate)
            original.extension.equals(EsdeCollectionCodec.EXTENSION, ignoreCase = true) ->
                EsdeCollectionCodec().readCandidate(candidate, original.nameWithoutExtension)
            original.name.equals("es_settings.xml", ignoreCase = true) ->
                EsdeSettingsEditor().read(candidate, emptySet())
        }
    }

    private fun validateSidecarCandidate(candidate: File, original: File) {
        require(candidate.length() in 1..EsdeSidecarStore.MAX_JSON_BYTES) { "Invalid sidecar size" }
        val state = candidate.reader(StandardCharsets.UTF_8).use { gson.fromJson(it, EsdeGameState::class.java) }
            ?: throw IllegalArgumentException("Empty sidecar")
        require(state.schemaVersion == EsdeGameState.SCHEMA_VERSION) { "Unsupported sidecar schema" }
        val normalized = EsdePathPolicy.normalizeGamePath(state.game)
        require(state.game == normalized) { "Sidecar game path is not canonical" }
        require(state.players == null || EsdeMetadataValidation.isValidPlayers(state.players)) { "Invalid players value" }
        require(state.rating == null || state.rating in 0.0..1.0) { "Invalid rating value" }
        val systemDirectory = generateSequence(original.parentFile) { it.parentFile }
            .firstOrNull { it.name == EsdeSidecarStore.SIDECAR_DIRECTORY }
            ?.parentFile ?: throw IllegalArgumentException("Sidecar is outside .esde-sync")
        require(EsdePathPolicy.sidecarFile(systemDirectory, normalized).canonicalFile == original.canonicalFile) {
            "Sidecar path does not match its game"
        }
    }

    private fun validateSharedSettings(candidate: File) {
        require(candidate.length() in 1..MAX_SHARED_SETTINGS_BYTES) { "Invalid shared settings size" }
        val root = candidate.reader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it) }
        require(root.isJsonObject) { "Shared settings must be an object" }
        val objectRoot = root.asJsonObject
        require(objectRoot.keySet() == setOf("schemaVersion", "settings")) { "Unknown shared settings field" }
        require(objectRoot["schemaVersion"].asInt == EsdeSharedSettingsProfile.SCHEMA_VERSION) {
            "Unsupported shared settings schema"
        }
        val settings = objectRoot["settings"]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Missing shared settings map")
        settings.entrySet().forEach { (name, element) ->
            val spec = EsdeSharedSettingsCatalog.requireAllowed(name)
            require(element.isJsonObject) { "$name must be an object" }
            val entry = element.asJsonObject
            require(entry.keySet() == setOf("type", "value")) { "$name has unknown fields" }
            require(entry["type"].asString == spec.type) { "$name has the wrong type" }
            val value = entry["value"] ?: throw IllegalArgumentException("$name has no value")
            val typed: Any = when (spec.type) {
                "bool" -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                "int" -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                "float" -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                "string" -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                else -> null
            } ?: throw IllegalArgumentException("$name has an invalid value")
            spec.normalize(typed)
        }
    }

    private fun resolveFiles(folderRoot: File, relativePath: String): Files {
        require(relativePath.isNotBlank()) { "Missing conflict path" }
        val root = folderRoot.canonicalFile
        require(root.isDirectory) { "Folder root is unavailable" }
        val normalized = relativePath.replace('\\', '/')
        require(!normalized.startsWith('/') && !DRIVE_PREFIX.containsMatchIn(normalized)) { "Absolute conflict path" }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe conflict path" }
        val conflict = File(root, normalized).canonicalFile
        requireInside(root, conflict)
        val originalName = conflict.name.replaceFirst(CONFLICT_MARKER, "")
        require(originalName != conflict.name && originalName.isNotBlank()) { "Invalid conflict filename" }
        val original = File(conflict.parentFile, originalName).canonicalFile
        requireInside(root, original)
        return Files(
            root = root,
            conflict = conflict,
            original = original,
            relativePath = normalized,
            originalRelativePath = original.relativeTo(root).path.replace('\\', '/'),
        )
    }

    private fun requireInside(root: File, child: File) {
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        require(child.path.startsWith(prefix)) { "Conflict path escaped its folder" }
    }

    private data class Files(
        val root: File,
        val conflict: File,
        val original: File,
        val relativePath: String,
        val originalRelativePath: String,
    ) {
        val isGamelist: Boolean = original.name.equals("gamelist.xml", ignoreCase = true)
    }

    companion object {
        private val CONFLICT_MARKER = Regex("\\.sync-conflict-(\\d{8})-(\\d{6})-([A-Za-z0-9]+)")
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
        private const val MAX_SHARED_SETTINGS_BYTES = 256L * 1024L
        private const val MAX_BATCH_FILES = 1000
    }
}

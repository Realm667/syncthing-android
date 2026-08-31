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
        require(files.conflict.isFile) { "Conflict copy no longer exists" }
        val isGamelist = files.original.name.equals("gamelist.xml", ignoreCase = true)
        require(resolution != EsdeConflictResolution.USE_CONFLICT_COPY || !isGamelist) {
            "gamelist.xml always remains local and cannot be replaced from a conflict copy"
        }

        val backups = EsdePrivateFileBackup(backupRoot)
        backups.create("conflicts", files.conflict)
        files.original.takeIf(File::isFile)?.let { backups.create("conflicts", it) }

        if (resolution == EsdeConflictResolution.USE_CONFLICT_COPY) {
            validateReplacement(files.conflict, files.original)
            AtomicFileWriter.write(files.original) { output ->
                files.conflict.inputStream().use { it.copyTo(output) }
            }
        }
        check(files.conflict.delete()) { "Could not remove the resolved conflict copy" }
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
        return Files(root, conflict, original)
    }

    private fun requireInside(root: File, child: File) {
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        require(child.path.startsWith(prefix)) { "Conflict path escaped its folder" }
    }

    private data class Files(val root: File, val conflict: File, val original: File)

    companion object {
        private val CONFLICT_MARKER = Regex("\\.sync-conflict-(\\d{8})-(\\d{6})-([A-Za-z0-9]+)")
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
        private const val MAX_SHARED_SETTINGS_BYTES = 256L * 1024L
    }
}

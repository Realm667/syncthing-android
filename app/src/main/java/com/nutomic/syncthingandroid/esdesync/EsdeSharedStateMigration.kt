package com.nutomic.syncthingandroid.esdesync

import com.google.gson.JsonParser
import java.io.File

data class EsdeSharedStateMigrationResult(
    val copied: Int = 0,
    val skipped: Int = 0,
    val conflicts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val successful: Boolean get() = conflicts.isEmpty() && errors.isEmpty()
}

internal class EsdeSharedStateMigration(
    private val backups: EsdePrivateFileBackup,
    private val collectionCodec: EsdeCollectionCodec = EsdeCollectionCodec(),
) {
    fun migrate(legacySyncRoot: File, destinationSyncRoot: File): EsdeSharedStateMigrationResult {
        val legacy = File(legacySyncRoot, EsdeGlobalLayout.DIRECTORY)
        val destination = File(destinationSyncRoot, EsdeGlobalLayout.DIRECTORY)
        if (!legacy.isDirectory) return EsdeSharedStateMigrationResult(skipped = 1)
        requireSeparateRoots(legacy, destination)
        val conflicts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var copied = 0
        var skipped = 0

        val candidates = buildList {
            File(legacy, EsdeGlobalLayout.COLLECTIONS_DIRECTORY).listFiles()?.filter {
                it.isFile && it.extension.equals(EsdeCollectionCodec.EXTENSION, true)
            }?.let(::addAll)
            File(File(legacy, EsdeGlobalLayout.SETTINGS_DIRECTORY), EsdeGlobalLayout.SETTINGS_FILE)
                .takeIf(File::isFile)?.let(::add)
        }
        candidates.forEach { source ->
            runCatching {
                validate(source)
                val relative = source.relativeTo(legacy).path
                val target = File(destination, relative)
                requireInside(destination, target)
                if (target.isFile) {
                    validate(target)
                    if (EsdeHashes.file(source) == EsdeHashes.file(target)) {
                        skipped++
                        return@runCatching
                    }
                    conflicts += relative
                    return@runCatching
                }
                backups.create("legacy-shared-state", source)
                AtomicFileWriter.write(target) { output -> source.inputStream().use { it.copyTo(output) } }
                copied++
            }.onFailure { errors += "${source.name} (${it.message ?: "invalid"})" }
        }
        return EsdeSharedStateMigrationResult(copied, skipped, conflicts.distinct(), errors.distinct())
    }

    private fun validate(file: File) {
        if (file.extension.equals(EsdeCollectionCodec.EXTENSION, true)) {
            collectionCodec.read(file)
            return
        }
        require(file.name == EsdeGlobalLayout.SETTINGS_FILE && file.length() in 1..EsdeSharedSettingsManager.MAX_PROFILE_BYTES)
        val root = file.reader().use { JsonParser.parseReader(it) }
        require(root.isJsonObject && root.asJsonObject["schemaVersion"]?.asInt == EsdeSharedSettingsProfile.SCHEMA_VERSION) {
            "Invalid shared settings profile"
        }
    }

    private fun requireSeparateRoots(source: File, destination: File) {
        require(source.canonicalFile != destination.canonicalFile) { "Legacy and destination folders are identical" }
        require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Destination is inside legacy data" }
        require(!source.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "Legacy data is inside destination" }
    }

    private fun requireInside(root: File, child: File) {
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(child.canonicalPath.startsWith(prefix)) { "Migration path escaped its root" }
    }
}

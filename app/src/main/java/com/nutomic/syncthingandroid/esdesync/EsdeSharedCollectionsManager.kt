package com.nutomic.syncthingandroid.esdesync

import java.io.File

internal class EsdeSharedCollectionsManager(
    gamelistRoot: File,
    private val esdeRoot: File,
    private val snapshots: EsdeSharedSnapshotStore,
    private val backups: EsdePrivateFileBackup,
    private val codec: EsdeCollectionCodec = EsdeCollectionCodec(),
) {
    private val sharedRoot = File(File(gamelistRoot, EsdeGlobalLayout.DIRECTORY), EsdeGlobalLayout.COLLECTIONS_DIRECTORY)
    private val localRoot = File(esdeRoot, "collections")

    init {
        requireRootContains(gamelistRoot, sharedRoot)
        requireRootContains(esdeRoot, localRoot)
    }

    fun discover(): Set<String> = sequenceOf(sharedRoot, localRoot).flatMap { root ->
        root.listFiles()?.asSequence().orEmpty()
    }.filter { it.isFile && it.extension.equals(EsdeCollectionCodec.EXTENSION, true) }
        .mapNotNull { runCatching { codec.read(it).name }.getOrNull() }.toSortedSet()

    fun publish(selected: Set<String>): EsdeSharedOperationResult = transfer(selected, importing = false)

    fun importSelected(selected: Set<String>, settingsFile: File): EsdeSharedOperationResult {
        val result = transfer(selected, importing = true)
        val validLocal = selected.filterTo(mutableSetOf()) { name ->
            runCatching {
                val local = File(localRoot, "$name.${EsdeCollectionCodec.EXTENSION}")
                val shared = File(sharedRoot, local.name)
                codec.read(local)
                codec.read(shared)
                EsdeHashes.file(local) == EsdeHashes.file(shared)
            }.getOrDefault(false)
        }
        val editor = EsdeSettingsEditor()
        val enabled = editor.read(settingsFile, setOf(COLLECTION_SETTING))[COLLECTION_SETTING]?.value
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet().orEmpty()
        if ((validLocal - enabled).isNotEmpty()) {
            backups.create("settings", settingsFile)
            editor.mergeCommaSeparated(settingsFile, COLLECTION_SETTING, validLocal)
        }
        return result
    }

    private fun transfer(selected: Set<String>, importing: Boolean): EsdeSharedOperationResult {
        val conflicts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var applied = 0
        var skipped = 0
        val sourceRoot = if (importing) sharedRoot else localRoot
        val targetRoot = if (importing) localRoot else sharedRoot
        if (sharedRoot.isDirectory) {
            sharedRoot.listFiles()?.filter { it.isFile && it.name.contains(".sync-conflict-") }
                ?.forEach { conflicts += it.name }
        }
        selected.sorted().forEach { requestedName ->
            try {
                codec.validateName(requestedName)
                val source = File(sourceRoot, "$requestedName.${EsdeCollectionCodec.EXTENSION}")
                val sourceDefinition = codec.read(source)
                val target = File(targetRoot, source.name)
                requireInside(sourceRoot, source)
                requireInside(targetRoot, target)
                val sourceHash = EsdeHashes.file(source)
                val targetHash = target.takeIf { it.isFile }?.let {
                    codec.read(it)
                    EsdeHashes.file(it)
                }
                if (targetHash == sourceHash) {
                    snapshots.save("collections", sourceDefinition.name, EsdeSharedSnapshot(sourceHash, sourceHash))
                    skipped++
                    return@forEach
                }
                val snapshot = snapshots.load("collections", sourceDefinition.name)
                if (targetHash != null && (snapshot == null ||
                        (importing && targetHash != snapshot.localHash) ||
                        (!importing && targetHash != snapshot.sharedHash))) {
                    conflicts += sourceDefinition.name
                    return@forEach
                }
                if (target.isFile) backups.create("collections", target)
                AtomicFileWriter.write(target) { output -> source.inputStream().use { it.copyTo(output) } }
                val copiedHash = EsdeHashes.file(target)
                snapshots.save("collections", sourceDefinition.name, EsdeSharedSnapshot(copiedHash, copiedHash))
                applied++
            } catch (error: Exception) {
                errors += "$requestedName (${error.message ?: "invalid"})"
            }
        }
        return EsdeSharedOperationResult(selected.size, applied, skipped, conflicts.distinct(), errors.distinct())
    }

    private fun requireInside(root: File, child: File) {
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(child.canonicalPath.startsWith(prefix)) { "Collection path escaped its root" }
    }

    private fun requireRootContains(root: File, child: File) {
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(child.canonicalPath.startsWith(prefix)) { "Shared collection root escaped its configured root" }
    }

    companion object { const val COLLECTION_SETTING = "CollectionSystemsCustom" }
}

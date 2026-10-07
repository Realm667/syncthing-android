package com.nutomic.syncthingandroid.esdesync

import java.io.File

/** One scan per local index revision. Failed scans never masquerade as an empty inventory. */
class EsdeConflictInventory(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val revision: String, val at: Long, val files: Array<String>)
    private val cache = mutableMapOf<String, Entry>()

    @Synchronized fun invalidate(path: String) { cache.remove(path) }

    @Synchronized fun scan(path: String, revision: String): Array<String> {
        val timestamp = now()
        cache[path]?.takeIf { it.revision == revision && timestamp - it.at < 30_000 }?.let { return it.files.copyOf() }
        val root = File(path).canonicalFile
        require(root.isDirectory && root.canRead()) { "Cannot read sync folder: $path" }
        val pending = ArrayDeque<File>()
        pending.add(root)
        val files = mutableListOf<String>()
        var visited = 0
        while (pending.isNotEmpty()) {
            check(!Thread.currentThread().isInterrupted) { "Conflict scan interrupted" }
            val directory = pending.removeLast()
            val children = directory.listFiles() ?: error("Cannot inspect ${directory.name}; check storage permissions")
            for (child in children) {
                check(++visited <= 1_000_000) { "Conflict scan limit reached; split the sync folder" }
                // Do not follow symlinks (including cycles and links outside the configured root).
                if (child.canonicalFile != child.absoluteFile) continue
                if (child.isDirectory) {
                    if (child.name !in setOf(".stversions", ".stfolder")) pending.add(child)
                } else if (child.isFile && CONFLICT.containsMatchIn(child.name)) {
                    files += child.relativeTo(root).invariantSeparatorsPath
                }
            }
        }
        val result = files.sorted().toTypedArray()
        cache[path] = Entry(revision, timestamp, result)
        return result.copyOf()
    }
    companion object { private val CONFLICT = Regex("\\.sync-conflict-[0-9]{8}-[0-9]{6}-[A-Za-z0-9]{7}") }
}

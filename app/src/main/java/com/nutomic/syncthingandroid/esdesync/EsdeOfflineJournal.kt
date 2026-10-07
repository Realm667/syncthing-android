package com.nutomic.syncthingandroid.esdesync

import com.google.gson.Gson
import java.io.File

enum class EsdeOfflineJournalStatus { PLAYING, PENDING, RECONCILING }
enum class EsdeSessionStep { CLOSE_ESDE, EXPORT_METADATA, PUBLISH_SHARED_STATE, SYNC_FILES }

data class EsdeOfflineJournalEntry(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val folderIds: Set<String> = emptySet(),
    val status: EsdeOfflineJournalStatus = EsdeOfflineJournalStatus.PLAYING,
    val lastError: String = "",
    val nextStep: EsdeSessionStep? = EsdeSessionStep.CLOSE_ESDE,
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

class EsdeOfflineJournal(private val file: File, private val gson: Gson = Gson()) {
    @Synchronized fun load(): EsdeOfflineJournalEntry? {
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..MAX_BYTES) { "Offline journal is damaged; restore its private backup before continuing" }
        return runCatching {
            file.reader().use { gson.fromJson(it, EsdeOfflineJournalEntry::class.java) }
        }.getOrElse { throw IllegalStateException("Offline journal cannot be read; pending changes must be recovered", it) }
            ?.let {
                require(it.schemaVersion == EsdeOfflineJournalEntry.SCHEMA_VERSION &&
                    it.sessionId.isNotBlank() && it.folderIds.size <= MAX_FOLDERS &&
                    it.startedAt >= 0 && it.endedAt >= 0 &&
                    (it.status as EsdeOfflineJournalStatus?) != null) {
                    "Offline journal contains invalid session data"
                }
                // Gson bypasses Kotlin defaults for older serialized objects.
                it.copy(lastError = (it.lastError as String?).orEmpty(), nextStep = it.nextStep ?: EsdeSessionStep.CLOSE_ESDE)
            } ?: error("Offline journal is empty or invalid")
    }

    @Synchronized fun advance(step: EsdeSessionStep) {
        val entry = load() ?: error("Missing session journal")
        save(entry.copy(nextStep = step))
    }

    @Synchronized fun begin(sessionId: String, folderIds: Set<String>, now: Long = System.currentTimeMillis()) {
        require(sessionId.isNotBlank())
        require(folderIds.size <= MAX_FOLDERS)
        save(EsdeOfflineJournalEntry(sessionId = sessionId, startedAt = now, folderIds = folderIds))
    }

    @Synchronized fun markPending(error: String = "", now: Long = System.currentTimeMillis()) {
        load()?.let { save(it.copy(endedAt = now, status = EsdeOfflineJournalStatus.PENDING, lastError = error.take(MAX_ERROR))) }
    }

    @Synchronized fun markReconciling() {
        load()?.let { save(it.copy(status = EsdeOfflineJournalStatus.RECONCILING, lastError = "")) }
    }

    @Synchronized fun migratePending(sessionId: String, folderIds: Set<String>) {
        if (load() == null) {
            begin(sessionId.ifBlank { "legacy-pending-${System.currentTimeMillis()}" }, folderIds)
            markPending("Recovered pending changes from an earlier SafeSync version")
        }
    }

    @Synchronized fun clear() {
        if (file.exists()) check(file.delete()) { "Could not clear offline journal" }
    }

    private fun save(entry: EsdeOfflineJournalEntry) {
        if (file.isFile) {
            load() // Preserve only a validated prior checkpoint, never a damaged journal.
            AtomicFileWriter.write(File(file.parentFile, "${file.name}.backup")) { output ->
                file.inputStream().use { it.copyTo(output) }
            }
        }
        AtomicFileWriter.write(file) { output ->
            output.writer(Charsets.UTF_8).apply { gson.toJson(entry, this); append('\n'); flush() }
        }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024L
        private const val MAX_FOLDERS = 128
        private const val MAX_ERROR = 2048
    }
}

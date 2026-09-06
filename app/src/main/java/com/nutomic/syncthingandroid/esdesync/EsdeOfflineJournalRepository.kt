package com.nutomic.syncthingandroid.esdesync

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EsdeJournalSnapshot(val loaded: Boolean = false, val entry: EsdeOfflineJournalEntry? = null)

/** Process-wide serial disk access. Rendering reads only the immutable, persisted snapshot. */
class EsdeOfflineJournalRepository private constructor(file: File) {
    private val journal = EsdeOfflineJournal(file)
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "ESDESync-Journal").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(EsdeJournalSnapshot())
    val state = mutableState.asStateFlow()

    fun update(action: (EsdeOfflineJournal) -> Unit, callback: (Result<EsdeOfflineJournalEntry?>) -> Unit) {
        executor.execute {
            val result = runCatching {
                action(journal)
                journal.load().also { mutableState.value = EsdeJournalSnapshot(true, it) }
            }
            callback(result)
        }
    }

    companion object {
        private val instances = mutableMapOf<String, EsdeOfflineJournalRepository>()
        @Synchronized fun get(file: File): EsdeOfflineJournalRepository =
            instances.getOrPut(file.absolutePath) { EsdeOfflineJournalRepository(file) }
    }
}

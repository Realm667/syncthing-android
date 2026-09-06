package com.nutomic.syncthingandroid.esdesync

/** One queued job per key, with at most one trailing pass for changes during execution. */
class EsdeCoalescingQueue<K>(
    private val schedule: (() -> Unit) -> Unit,
    private val action: (K) -> Unit,
) {
    private data class Pending(var running: Boolean = false, var dirty: Boolean = false)
    private val pending = mutableMapOf<K, Pending>()
    private var closed = false

    @Synchronized fun submit(key: K) {
        if (closed) return
        pending[key]?.let {
            if (it.running) it.dirty = true
            return
        }
        pending[key] = Pending()
        enqueue(key)
    }

    private fun enqueue(key: K) {
        try {
            schedule { run(key) }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            pending.remove(key)
            if (!closed) throw error
        }
    }

    private fun run(key: K) {
        synchronized(this) {
            if (closed) return
            val entry = pending[key] ?: return
            entry.running = true
            entry.dirty = false
        }
        try {
            action(key)
        } finally {
            synchronized(this) {
                val entry = pending[key]
                if (!closed && entry?.dirty == true) {
                    entry.running = false
                    enqueue(key)
                } else pending.remove(key)
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        pending.clear()
    }
}

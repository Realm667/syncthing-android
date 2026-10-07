package com.nutomic.syncthingandroid.esdesync

/** Resumable finalization. A durable checkpoint follows each successful operation. */
class EsdeSessionWorkflow(
    private val perform: (EsdeSessionStep, (Result<Unit>) -> Unit) -> Unit,
    private val checkpoint: (EsdeSessionStep, () -> Unit) -> Unit,
    private val failed: (String) -> Unit,
    private val readyToSync: () -> Unit,
) {
    fun resume(step: EsdeSessionStep) {
        if (step == EsdeSessionStep.SYNC_FILES) {
            readyToSync()
            return
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish: (Result<Unit>) -> Unit = finish@{ result ->
            if (!completed.compareAndSet(false, true)) return@finish
            result.fold(onSuccess = {
                val next = when (step) {
                    EsdeSessionStep.CLOSE_ESDE -> EsdeSessionStep.EXPORT_METADATA
                    EsdeSessionStep.EXPORT_METADATA -> EsdeSessionStep.PUBLISH_SHARED_STATE
                    else -> EsdeSessionStep.SYNC_FILES
                }
                try { checkpoint(next) { resume(next) } }
                catch (error: Exception) { failed("Could not save $next checkpoint: ${error.message}") }
            }, onFailure = { failed("$step: ${it.message}") })
        }
        try { perform(step, finish) }
        catch (error: Exception) { finish(Result.failure(error)) }
    }
}

/** Elapsed monotonic time, not total session duration, controls a stalled-transfer warning. */
class EsdeProgressWatchdog(private val timeoutMs: Long = 120_000) {
    private var lastProgress = 0L
    private var previous: Any? = null
    fun stalled(observation: Any, now: Long): Boolean {
        if (previous != observation) { previous = observation; lastProgress = now }
        return now - lastProgress >= timeoutMs
    }
    fun reset() { previous = null; lastProgress = 0 }
}

class EsdeTransferMeter {
    data class Rate(val download: Double, val upload: Double)
    private var previous: Triple<Long, Long, Long>? = null
    fun sample(download: Long, upload: Long, nowMs: Long): Rate? {
        val old = previous
        previous = Triple(download, upload, nowMs)
        if (old == null || nowMs <= old.third || download < old.first || upload < old.second) return null
        val seconds = (nowMs - old.third) / 1000.0
        return Rate((download - old.first) / seconds, (upload - old.second) / seconds)
    }
    fun reset() { previous = null }
}

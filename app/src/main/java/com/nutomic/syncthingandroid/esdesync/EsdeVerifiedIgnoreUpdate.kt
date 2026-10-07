package com.nutomic.syncthingandroid.esdesync

/** A successful POST alone is insufficient: verify the effective rules with a fresh GET. */
internal class EsdeVerifiedIgnoreUpdate(
    private val write: (List<String>, () -> Unit, () -> Unit) -> Unit,
    private val read: ((List<String>) -> Unit, () -> Unit) -> Unit,
) {
    fun apply(rules: List<String>, valid: (List<String>) -> Boolean, callback: (Boolean) -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(ok: Boolean) { if (finished.compareAndSet(false, true)) callback(ok) }
        try {
            write(rules, {
                try { read({ current -> finish(runCatching { valid(current) }.getOrDefault(false)) }, { finish(false) }) }
                catch (_: Exception) { finish(false) }
            }, { finish(false) })
        } catch (_: Exception) { finish(false) }
    }
}

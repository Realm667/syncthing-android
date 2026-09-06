package com.nutomic.syncthingandroid.esdesync

/** Back off only while the complete observed state is unchanged; progress resets the interval. */
class EsdePollPolicy(private val minimumMs: Long = 1_500, private val maximumMs: Long = 12_000) {
    private var previous: Any? = null
    private var interval = minimumMs
    fun nextDelay(observation: Any): Long {
        interval = if (observation == previous) (interval * 2).coerceAtMost(maximumMs) else minimumMs
        previous = observation
        return interval
    }
    fun reset() { previous = null; interval = minimumMs }
}

/** Request lifetime, not UI/gate state. Invalidated callbacks cannot publish into another run. */
class EsdeRequestGeneration {
    @Volatile var current: Long = 0
        private set
    @Synchronized fun invalidate() { current++ }
    fun accepts(token: Long): Boolean = token == current
}

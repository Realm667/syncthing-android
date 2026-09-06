package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

class EsdeOptimizationTest {
    @Test fun lateReadinessDoesNotLeaveIdleOrInterruptPlay() {
        val idle = EsdeSafeLaunchCompletionPolicy.afterDone(EsdeSyncState.SAFE_TO_SWITCH)
        assertFalse(EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(idle))
        assertFalse(EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(EsdeSyncState.SAFE_TO_SWITCH))
        assertFalse(EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(EsdeSyncState.ESDE_RUNNING))
        assertFalse(EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(EsdeSyncState.OFFLINE_PLAYING))
        assertTrue(EsdeSafeLaunchCompletionPolicy.canResumeAutomatically(EsdeSyncState.STARTING))
    }

    @Test fun readinessTracksSlowInitializationAndConfigChangesWithoutATimer() {
        val readiness = EsdeServiceReadiness()
        assertFalse(readiness.state.value.apiReady)
        readiness.publish(true, false)
        assertTrue(readiness.state.value.apiReady)
        assertFalse(readiness.state.value.coordinatorReady)
        readiness.publish(true, true)
        val active = readiness.state.value
        readiness.publish(true, true)
        assertTrue(readiness.state.value.coordinatorReady)
        assertEquals(active.revision + 1, readiness.state.value.revision)
        readiness.publish(false, false)
        assertFalse(readiness.state.value.apiReady)
    }

    @Test fun sidecarBurstRunsOncePerSystemWithOneTrailingPass() {
        val scheduled = ArrayDeque<() -> Unit>()
        val imported = mutableListOf<String>()
        lateinit var queue: EsdeCoalescingQueue<String>
        queue = EsdeCoalescingQueue({ scheduled.add(it) }) { system ->
            imported += system
            if (imported.size == 1) repeat(100) { queue.submit(system) }
        }
        repeat(1000) { queue.submit("snes") }
        queue.submit("nes")
        assertEquals(2, scheduled.size)
        scheduled.removeFirst()()
        assertEquals(2, scheduled.size) // NES plus a single trailing SNES pass.
        while (scheduled.isNotEmpty()) scheduled.removeFirst()()
        assertEquals(listOf("snes", "nes", "snes"), imported)
    }

    @Test fun stoppedQueueDropsPendingWorkAndRejectsNewEvents() {
        val scheduled = ArrayDeque<() -> Unit>()
        var imports = 0
        val queue = EsdeCoalescingQueue<String>({ scheduled.add(it) }) { imports++ }
        queue.submit("snes")
        queue.close()
        queue.submit("nes")
        scheduled.removeFirst()()
        assertEquals(0, imports)
        assertTrue(scheduled.isEmpty())
    }

    @Test fun failedImportDoesNotLeaveTheSystemPermanentlyQueued() {
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = EsdeCoalescingQueue<String>({ scheduled.add(it) }) { error("invalid input") }
        queue.submit("snes")
        assertTrue(runCatching { scheduled.removeFirst()() }.isFailure)
        queue.submit("snes")
        assertEquals(1, scheduled.size)
    }

    @Test fun diagnosticsOnlyReinspectChangedOrNewSystemsAndDiscardRemovedRoots() {
        val cache = EsdeDiagnosticsCache()
        val first = File("roms/snes")
        val second = File("roms/nes")
        val reads = mutableListOf<File>()
        val inspect: (File) -> EsdeSystemDiagnostics = {
            reads += it
            EsdeSystemDiagnostics(4, 1, 2, 1)
        }
        assertEquals(8, cache.refresh(listOf(first, second), true, inspect).sidecarsTotal)
        reads.clear()
        cache.invalidate(first)
        cache.refresh(listOf(first, second), false, inspect)
        assertEquals(listOf(first.absoluteFile), reads)
        reads.clear()
        assertEquals(4, cache.refresh(listOf(second), false, inspect).sidecarsTotal)
        assertTrue(reads.isEmpty())
        val movedRoot = File("other/nes")
        cache.refresh(listOf(movedRoot), false, inspect)
        assertEquals(listOf(movedRoot.absoluteFile), reads)
        reads.clear()
        cache.refresh(listOf(movedRoot), true, inspect)
        assertEquals(1, reads.size)
    }

    @Test fun unchangedPollingBacksOffButProgressAndNewSessionsResetIt() {
        val policy = EsdePollPolicy()
        assertEquals(listOf(1500L, 3000L, 6000L, 12000L, 12000L), List(5) { policy.nextDelay("waiting") })
        assertEquals(1500L, policy.nextDelay("progress"))
        policy.reset()
        assertEquals(1500L, policy.nextDelay("progress"))
    }

    @Test fun lateRepliesCannotBelongToRetriedOrEndedSessions() {
        val requests = EsdeRequestGeneration()
        val old = requests.current
        assertTrue(requests.accepts(old))
        requests.invalidate()
        assertFalse(requests.accepts(old))
        val retry = requests.current
        requests.invalidate()
        assertFalse(requests.accepts(retry))
        assertTrue(requests.accepts(requests.current))
    }
}

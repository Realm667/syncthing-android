package com.nutomic.syncthingandroid.esdesync

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EsdeOfflineJournalRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun initialFailureCanBeRetriedWithoutAnExistingSnapshot() {
        val repository = EsdeOfflineJournalRepository.get(File(temporary.root, "journal.json"))
        val failed = CountDownLatch(1)
        repository.update({ error("temporary storage failure") }) { failed.countDown() }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertFalse(repository.state.value.loaded)
        val retried = CountDownLatch(1)
        repository.update({}) { retried.countDown() }
        assertTrue(retried.await(5, TimeUnit.SECONDS))
        assertTrue(repository.state.value.loaded)
        assertNull(repository.state.value.entry)
    }

    @Test fun diskWorkIsSerializedOffCallerThreadAndSnapshotFollowsDurableWrites() {
        val file = File(temporary.root, "journal.json")
        val repository = EsdeOfflineJournalRepository.get(file)
        assertSame(repository, EsdeOfflineJournalRepository.get(file))
        assertFalse(repository.state.value.loaded)
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        repository.update({
            worker.set(Thread.currentThread())
            it.begin("test", setOf("roms"), 100)
        }) { if (it.isFailure) failure.set(it.exceptionOrNull()) }
        repository.update({ it.markPending("offline", 200) }) { result ->
            if (result.isFailure) failure.set(result.exceptionOrNull())
            completed.countDown()
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertNotSame(caller, worker.get())
        assertTrue(repository.state.value.loaded)
        assertEquals(EsdeOfflineJournal(file).load(), repository.state.value.entry)
        assertEquals(EsdeOfflineJournalStatus.PENDING, repository.state.value.entry!!.status)
    }

    @Test fun failedMutationDoesNotPublishSuccessAndDoesNotBlockRetry() {
        val file = File(temporary.root, "journal.json")
        val repository = EsdeOfflineJournalRepository.get(file)
        val begin = CountDownLatch(1)
        repository.update({ it.begin("saved", setOf("roms")) }) { begin.countDown() }
        assertTrue(begin.await(5, TimeUnit.SECONDS))
        val saved = repository.state.value
        val failed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        repository.update({ error("storage unavailable") }) {
            failure.set(it.exceptionOrNull())
            failed.countDown()
        }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertNotNull(failure.get())
        assertEquals(saved, repository.state.value)
        val cleared = CountDownLatch(1)
        repository.update({ it.clear() }) { cleared.countDown() }
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertNull(repository.state.value.entry)
        assertFalse(file.exists())
    }
}

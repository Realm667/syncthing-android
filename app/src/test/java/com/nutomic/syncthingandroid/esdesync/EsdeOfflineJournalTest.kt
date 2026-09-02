package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeOfflineJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pendingSessionSurvivesStoreRecreationAndClearsOnlyExplicitly() {
        val file = File(temporary.root, "private/offline-journal.json")
        EsdeOfflineJournal(file).apply {
            begin("session-1", setOf("roms", "saves"), now = 100)
            markPending("primary unavailable", now = 200)
        }

        val recovered = EsdeOfflineJournal(file).load()!!
        assertEquals("session-1", recovered.sessionId)
        assertEquals(100L, recovered.startedAt)
        assertEquals(200L, recovered.endedAt)
        assertEquals(EsdeOfflineJournalStatus.PENDING, recovered.status)
        assertEquals(setOf("roms", "saves"), recovered.folderIds)

        EsdeOfflineJournal(file).clear()
        assertNull(EsdeOfflineJournal(file).load())
    }

    @Test fun legacyPendingFlagCanBeMigratedWithoutOverwritingAnExistingJournal() {
        val journal = EsdeOfflineJournal(File(temporary.root, "journal.json"))
        journal.migratePending("legacy", setOf("roms"))
        assertEquals(EsdeOfflineJournalStatus.PENDING, journal.load()!!.status)
        journal.migratePending("replacement", setOf("other"))
        assertEquals("legacy", journal.load()!!.sessionId)
        assertTrue(journal.load()!!.lastError.contains("Recovered"))
    }
}

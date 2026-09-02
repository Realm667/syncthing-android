package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdePowerOffPolicyTest {
    @Test fun powerOffIsAllowedOnlyForACompletedIdleSession() {
        assertTrue(
            EsdePowerOffPolicy.canRequest(
                state = EsdeSyncState.IDLE,
                esdeWasLaunched = false,
                pendingLocalChanges = false,
                hasOfflineJournal = false,
                activeSessionId = "",
            )
        )
        assertFalse(safeToRequest(state = EsdeSyncState.SAFE_TO_SWITCH))
        assertFalse(safeToRequest(esdeWasLaunched = true))
        assertFalse(safeToRequest(pendingLocalChanges = true))
        assertFalse(safeToRequest(hasOfflineJournal = true))
        assertFalse(safeToRequest(activeSessionId = "unfinished-session"))
    }

    private fun safeToRequest(
        state: EsdeSyncState = EsdeSyncState.IDLE,
        esdeWasLaunched: Boolean = false,
        pendingLocalChanges: Boolean = false,
        hasOfflineJournal: Boolean = false,
        activeSessionId: String = "",
    ): Boolean = EsdePowerOffPolicy.canRequest(
        state,
        esdeWasLaunched,
        pendingLocalChanges,
        hasOfflineJournal,
        activeSessionId,
    )
}

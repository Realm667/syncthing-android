package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySyncthingMigrationTest {
    @Test
    fun offersMigrationOnlyForFreshInstallBesideLegacyPackage() {
        assertTrue(LegacySyncthingMigration.shouldOffer(false, true))
        assertFalse(LegacySyncthingMigration.shouldOffer(true, true))
        assertFalse(LegacySyncthingMigration.shouldOffer(false, false))
    }
}

package com.geovault.tracker.settings

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSettingsDocumentTest {

    @Test
    fun fromLegacy_readsPreferenceDatastoreKeys() {
        val document = TrackerSettingsDocument.fromLegacy(
            GeoVaultLegacySettingsBlob(
                boolValues = mapOf(
                    TrackerSettingsDocument.KEY_START_ON_BOOT to true,
                    TrackerSettingsDocument.KEY_START_TRACKING_ON_LAUNCH to true,
                    TrackerSettingsDocument.KEY_SPARSE_TRACKING to true,
                    TrackerSettingsDocument.KEY_WAS_TRACKING_BEFORE_EXIT to true,
                ),
                longValues = mapOf(
                    TrackerSettingsDocument.KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC to 90L,
                ),
            )
        )

        assertTrue(document.startOnBoot)
        assertTrue(document.startTrackingOnLaunch)
        assertTrue(document.sparseTracking)
        assertTrue(document.wasTrackingBeforeExit)
        assertEquals(90L, document.lowAccuracyFallbackTimeoutSec)
        assertEquals(TrackerSettingsDefaults.schemaVersion, document.toRecord().schemaVersion)
    }
}

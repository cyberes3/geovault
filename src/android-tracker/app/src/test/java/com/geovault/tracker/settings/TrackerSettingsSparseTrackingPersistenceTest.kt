package com.geovault.tracker.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerSettingsSparseTrackingPersistenceTest {

    @Test
    fun writeAndRead_roundTripsSparseTrackingEnabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackerSettingsDataStore(context)

        store.writeSettings(TrackerSettings(sparseTracking = true))
        val record = store.readRecord()

        assertTrue(record.settings.sparseTracking)
    }

    @Test
    fun writeAndRead_roundTripsSparseTrackingDisabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackerSettingsDataStore(context)

        store.writeSettings(TrackerSettings(sparseTracking = false))
        val record = store.readRecord()

        assertFalse(record.settings.sparseTracking)
    }

    @Test
    fun toggle_sparseTracking_persistsLatestValue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackerSettingsDataStore(context)

        store.writeSettings(TrackerSettings(sparseTracking = true))
        store.writeSettings(TrackerSettings(sparseTracking = false))

        assertFalse(store.readRecord().settings.sparseTracking)
    }
}

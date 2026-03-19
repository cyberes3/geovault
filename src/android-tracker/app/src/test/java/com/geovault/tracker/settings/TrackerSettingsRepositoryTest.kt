package com.geovault.tracker.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerSettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(TrackerSettingsRepositoryImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun getSettings_readsLegacyKeysAndDefaults() {
        context.getSharedPreferences(TrackerSettingsRepositoryImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_INTERVAL, "20")
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_DISTANCE, "40")
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_ACCURACY, "80")
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_EXTENDED_PARAMS, false)
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_SIGNIFICANT_MOTION_ONLY, false)
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_RESTART_TRACKING_IF_KILLED, false)
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_AUTO_TRACKING_ENABLED, true)
            .putString(TrackerSettingsRepositoryImpl.KEY_TRACKING_PROFILE, "2")
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_START_ON_BOOT, true)
            .putBoolean(TrackerSettingsRepositoryImpl.KEY_START_TRACKING_ON_LAUNCH, true)
            .commit()

        val repository = TrackerSettingsRepositoryImpl(context)
        val settings = repository.getSettings()

        assertEquals(20L, settings.loggingIntervalSec)
        assertEquals(40f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(80f, settings.accuracyFilterMeters, 0.0001f)
        assertFalse(settings.sendExtendedData)
        assertFalse(settings.significantDataOnly)
        assertFalse(settings.resetTrackingIfKilled)
        assertTrue(settings.autoTrackingMode)
        assertEquals(TrackerTrackingProfile.DRIVING, settings.trackingProfile)
        assertTrue(settings.startOnBoot)
        assertTrue(settings.startTrackingOnLaunch)
    }

    @Test
    fun getSettings_clampsOutOfRangeValues() {
        context.getSharedPreferences(TrackerSettingsRepositoryImpl.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_INTERVAL, "0")
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_DISTANCE, "-10")
            .putString(TrackerSettingsRepositoryImpl.KEY_LOGGING_ACCURACY, "999999")
            .commit()

        val repository = TrackerSettingsRepositoryImpl(context)
        val settings = repository.getSettings()

        assertEquals(TrackerSettings.MIN_LOGGING_INTERVAL_SEC, settings.loggingIntervalSec)
        assertEquals(TrackerSettings.MIN_DISTANCE_FILTER_METERS, settings.distanceFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MAX_ACCURACY_FILTER_METERS, settings.accuracyFilterMeters, 0.0001f)
    }

    @Test
    fun setTrackingProfile_presetUpdatesNumericSettings() {
        val repository = TrackerSettingsRepositoryImpl(context)

        repository.setTrackingProfile(TrackerTrackingProfile.WALKING)
        var settings = repository.getSettings()
        assertEquals(TrackerTrackingProfile.WALKING, settings.trackingProfile)
        assertEquals(30L, settings.loggingIntervalSec)
        assertEquals(10f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(50f, settings.accuracyFilterMeters, 0.0001f)

        repository.setTrackingProfile(TrackerTrackingProfile.DRIVING)
        settings = repository.getSettings()
        assertEquals(TrackerTrackingProfile.DRIVING, settings.trackingProfile)
        assertEquals(10L, settings.loggingIntervalSec)
        assertEquals(100f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(200f, settings.accuracyFilterMeters, 0.0001f)
    }

    @Test
    fun setTrackingProfile_customPreservesNumericSettings() {
        val repository = TrackerSettingsRepositoryImpl(context)
        repository.setLoggingIntervalSec(22L)
        repository.setDistanceFilterMeters(33f)
        repository.setAccuracyFilterMeters(44f)

        repository.setTrackingProfile(TrackerTrackingProfile.CUSTOM)
        val settings = repository.getSettings()

        assertEquals(TrackerTrackingProfile.CUSTOM, settings.trackingProfile)
        assertEquals(22L, settings.loggingIntervalSec)
        assertEquals(33f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(44f, settings.accuracyFilterMeters, 0.0001f)
    }
}

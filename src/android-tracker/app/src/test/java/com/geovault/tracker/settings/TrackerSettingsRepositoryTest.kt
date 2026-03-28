package com.geovault.tracker.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.withContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var legacyPrefs: android.content.SharedPreferences
    private var sharedDataStore: TrackerSettingsDataStore? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        legacyPrefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().clear().commit()
        clearDataStoreFiles()
        sharedDataStore = null
    }

    @Test
    fun getSettings_initializesFromRedesignDefaults() {
        val repository = createRepository()
        waitUntilReady(repository)
        val settings = repository.getSettings()

        assertEquals(TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC, settings.loggingIntervalSec)
        assertEquals(TrackerSettings.DEFAULT_DISTANCE_FILTER_METERS, settings.distanceFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.DEFAULT_ACCURACY_FILTER_METERS, settings.accuracyFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC, settings.lowAccuracyFallbackTimeoutSec)
        assertTrue(settings.lowAccuracyFallbackEnabled)
        assertTrue(settings.sendExtendedData)
        assertTrue(settings.significantDataOnly)
        assertTrue(settings.autoTrackingMode)
        assertFalse(settings.startOnBoot)
        assertFalse(settings.startTrackingOnLaunch)
    }

    @Test
    fun schemaMismatch_hardResetsDatastoreWithoutLegacyCleanup() {
        withDataStore { resetToDefaults(0) }
        legacyPrefs.edit()
            .putString("server_url", "https://example.test")
            .putString("selected_tracker_id", "tracker-1")
            .putString("logging_interval", "99")
            .putBoolean("start_on_boot", true)
            .putBoolean("auto_tracking_enabled", false)
            .commit()

        val repository = createRepository()
        waitUntilReady(repository)
        val settings = repository.getSettings()

        assertEquals(TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC, settings.loggingIntervalSec)
        assertFalse(settings.startOnBoot)
        assertTrue(settings.autoTrackingMode)
        assertEquals("https://example.test", legacyPrefs.getString("server_url", null))
        assertEquals("tracker-1", legacyPrefs.getString("selected_tracker_id", null))
        assertEquals("99", legacyPrefs.getString("logging_interval", null))
        assertTrue(legacyPrefs.getBoolean("start_on_boot", false))
        assertFalse(legacyPrefs.getBoolean("auto_tracking_enabled", true))
    }

    @Test
    fun setters_persistTogglesAndWasTrackingState() {
        val repository = createRepository()
        waitUntilReady(repository)

        repository.setSendExtendedData(false)
        repository.setSignificantDataOnly(false)
        repository.setAutoTrackingMode(false)
        repository.setStartOnBoot(true)
        repository.setStartTrackingOnLaunch(true)
        repository.setKeepScreenOnWhileViewingMap(false)
        repository.setLowAccuracyFallbackEnabled(false)
        repository.setWasTrackingBeforeExit(true)
        waitUntil {
            repository.getSettings().startOnBoot &&
                repository.getSettings().startTrackingOnLaunch &&
                !repository.getSettings().autoTrackingMode &&
                repository.wasTrackingBeforeExit()
        }

        val reloaded = createRepository()
        waitUntilReady(reloaded)
        waitUntil {
            reloaded.getSettings().startOnBoot &&
                reloaded.getSettings().startTrackingOnLaunch &&
                !reloaded.getSettings().autoTrackingMode &&
                !reloaded.getSettings().lowAccuracyFallbackEnabled
        }
        val settings = reloaded.getSettings()
        assertFalse(settings.sendExtendedData)
        assertFalse(settings.significantDataOnly)
        assertFalse(settings.autoTrackingMode)
        assertTrue(settings.startOnBoot)
        assertTrue(settings.startTrackingOnLaunch)
        assertFalse(settings.keepScreenOnWhileViewingMap)
        assertFalse(settings.lowAccuracyFallbackEnabled)
        waitUntil { reloaded.wasTrackingBeforeExit() }
        assertTrue(reloaded.wasTrackingBeforeExit())

        reloaded.clearWasTrackingBeforeExit()
        waitUntil { !reloaded.wasTrackingBeforeExit() }
        assertFalse(reloaded.wasTrackingBeforeExit())
    }

    @Test
    fun numericSetters_clampOutOfRangeValues() {
        val repository = createRepository()
        waitUntilReady(repository)

        repository.setLoggingIntervalSec(0L)
        repository.setDistanceFilterMeters(-10f)
        repository.setAccuracyFilterMeters(999999f)
        repository.setLowAccuracyFallbackTimeoutSec(0L)
        waitUntil {
            repository.getSettings().loggingIntervalSec == TrackerSettings.MIN_LOGGING_INTERVAL_SEC &&
                repository.getSettings().distanceFilterMeters == TrackerSettings.MIN_DISTANCE_FILTER_METERS &&
                repository.getSettings().accuracyFilterMeters == TrackerSettings.MAX_ACCURACY_FILTER_METERS &&
                repository.getSettings().lowAccuracyFallbackTimeoutSec == TrackerSettings.MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC
        }

        val settings = repository.getSettings()
        assertEquals(TrackerSettings.MIN_LOGGING_INTERVAL_SEC, settings.loggingIntervalSec)
        assertEquals(TrackerSettings.MIN_DISTANCE_FILTER_METERS, settings.distanceFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MAX_ACCURACY_FILTER_METERS, settings.accuracyFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC, settings.lowAccuracyFallbackTimeoutSec)
    }

    @Test
    fun setTrackingProfile_presetUpdatesNumericSettings() {
        val repository = createRepository()
        waitUntilReady(repository)

        repository.setTrackingProfile(TrackerTrackingProfile.WALKING)
        waitUntil { repository.getSettings().trackingProfile == TrackerTrackingProfile.WALKING }
        var settings = repository.getSettings()
        assertEquals(TrackerTrackingProfile.WALKING, settings.trackingProfile)
        assertEquals(20L, settings.loggingIntervalSec)
        assertEquals(7f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(40f, settings.accuracyFilterMeters, 0.0001f)

        repository.setTrackingProfile(TrackerTrackingProfile.DRIVING)
        waitUntil { repository.getSettings().trackingProfile == TrackerTrackingProfile.DRIVING }
        settings = repository.getSettings()
        assertEquals(TrackerTrackingProfile.DRIVING, settings.trackingProfile)
        assertEquals(10L, settings.loggingIntervalSec)
        assertEquals(100f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(200f, settings.accuracyFilterMeters, 0.0001f)
    }

    @Test
    fun setTrackingProfile_customPreservesNumericSettings() {
        val repository = createRepository()
        waitUntilReady(repository)
        repository.setLoggingIntervalSec(22L)
        repository.setDistanceFilterMeters(33f)
        repository.setAccuracyFilterMeters(44f)
        waitUntil {
            repository.getSettings().loggingIntervalSec == 22L &&
                repository.getSettings().distanceFilterMeters == 33f &&
                repository.getSettings().accuracyFilterMeters == 44f
        }

        repository.setTrackingProfile(TrackerTrackingProfile.CUSTOM)
        waitUntil { repository.getSettings().trackingProfile == TrackerTrackingProfile.CUSTOM }
        val settings = repository.getSettings()

        assertEquals(TrackerTrackingProfile.CUSTOM, settings.trackingProfile)
        assertEquals(22L, settings.loggingIntervalSec)
        assertEquals(33f, settings.distanceFilterMeters, 0.0001f)
        assertEquals(44f, settings.accuracyFilterMeters, 0.0001f)
    }

    @Test
    fun concurrentToggleWrites_lastWriterWinsPersistedState() = runBlocking {
        val repository = createRepository()
        waitUntilReady(repository)

        withContext(Dispatchers.Default) {
            repeat(64) { index ->
                launch {
                    repository.setStartOnBoot(index % 2 == 0)
                }
            }
        }
        repository.setStartOnBoot(false)
        waitUntil { !repository.getSettings().startOnBoot }

        val reloaded = createRepository()
        waitUntilReady(reloaded)
        waitUntil { !reloaded.getSettings().startOnBoot }
        assertFalse(reloaded.getSettings().startOnBoot)
    }

    @Test
    fun corruptedDatastoreFile_recoversToDefaults() {
        val dataStoreFile = File(context.filesDir, "datastore/${TrackerSettingsDataStore.DATASTORE_NAME}.preferences_pb")
        dataStoreFile.parentFile?.mkdirs()
        dataStoreFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val repository = createRepository()
        waitUntilReady(repository)
        val settings = repository.getSettings()

        assertEquals(TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC, settings.loggingIntervalSec)
        assertTrue(settings.autoTrackingMode)
        assertFalse(settings.startOnBoot)
    }

    @Test
    fun repositoryEventuallyBecomesReady() {
        val repository = createRepository()
        waitUntilReady(repository)
        assertTrue(repository.isReady())
    }

    @Test
    fun repositoryState_hasLoadingOrReadyThenEventuallyReady() {
        val repository = createRepository()
        val initialLoadState = repository.getState().loadState
        assertTrue(
            initialLoadState == TrackerSettingsLoadState.Loading ||
                initialLoadState == TrackerSettingsLoadState.Ready
        )
        waitUntilReady(repository)
        assertEquals(TrackerSettingsLoadState.Ready, repository.getState().loadState)
        assertTrue(repository.getState().revision > 0L)
    }

    private fun clearDataStoreFiles() {
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return
        dataStoreDir.listFiles()?.forEach { file ->
            if (file.name.contains(TrackerSettingsDataStore.DATASTORE_NAME)) {
                file.delete()
            }
        }
    }

    private fun createRepository(): TrackerSettingsRepositoryImpl {
        return TrackerSettingsRepositoryImpl(
            dataStore = sharedDataStore ?: TrackerSettingsDataStore(context).also {
                sharedDataStore = it
            },
            writePolicy = TrackerSettingsWritePolicy()
        )
    }

    private fun withDataStore(block: suspend TrackerSettingsDataStore.() -> Unit) {
        kotlinx.coroutines.runBlocking {
            val store = sharedDataStore ?: TrackerSettingsDataStore(context).also {
                sharedDataStore = it
            }
            store.block()
        }
    }

    private fun waitUntilReady(repository: TrackerSettingsRepository, timeoutMs: Long = 3_000L) {
        waitUntil(timeoutMs) { repository.isReady() }
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}

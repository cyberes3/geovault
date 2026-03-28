package com.geovault.tracker.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class TrackerSettingsRecord(
    val settings: TrackerSettings,
    val wasTrackingBeforeExit: Boolean,
    val schemaVersion: Int
)

@Singleton
class TrackerSettingsDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            Log.e(
                TAG,
                "settings_datastore_corruption_recovered reason=replace_file_corruption",
                corruption
            )
            emptyPreferences()
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile(DATASTORE_NAME) }
    )

    fun observeRecord(): Flow<TrackerSettingsRecord> {
        return store.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map(::toRecord)
    }

    suspend fun readRecord(): TrackerSettingsRecord = observeRecord().first()

    suspend fun writeSettings(settings: TrackerSettings) {
        updateRecord(reason = "write_settings") { current ->
            current.copy(settings = settings)
        }
    }

    suspend fun setWasTrackingBeforeExit(value: Boolean) {
        updateRecord(reason = "set_was_tracking_before_exit") { current ->
            current.copy(wasTrackingBeforeExit = value)
        }
    }

    suspend fun clearWasTrackingBeforeExit() {
        updateRecord(reason = "clear_was_tracking_before_exit") { current ->
            current.copy(wasTrackingBeforeExit = false)
        }
    }

    suspend fun updateRecord(
        reason: String,
        transform: (TrackerSettingsRecord) -> TrackerSettingsRecord
    ) {
        store.edit { prefs ->
            val current = toRecord(prefs)
            val next = transform(current)
            Log.i(
                TAG,
                "datastore_write reason=$reason schema=${next.schemaVersion} wasTrackingBeforeExit=${next.wasTrackingBeforeExit} settings=${settingsSummary(next.settings)}"
            )
            writeRecordToPrefs(prefs, next)
        }
    }

    suspend fun resetToDefaults(schemaVersion: Int) {
        Log.w(TAG, "datastore_reset_to_defaults schema=$schemaVersion")
        val defaults = TrackerSettingsDefaults.baseline
        store.edit { prefs ->
            writeRecordToPrefs(
                prefs = prefs,
                record = TrackerSettingsRecord(
                    settings = defaults,
                    wasTrackingBeforeExit = false,
                    schemaVersion = schemaVersion
                )
            )
        }
    }

    private fun writeRecordToPrefs(prefs: androidx.datastore.preferences.core.MutablePreferences, record: TrackerSettingsRecord) {
        prefs.clear()
        prefs[KEY_SCHEMA_VERSION] = record.schemaVersion
        prefs[KEY_WAS_TRACKING_BEFORE_EXIT] = record.wasTrackingBeforeExit
        prefs[KEY_LOGGING_INTERVAL] = record.settings.loggingIntervalSec
        prefs[KEY_LOGGING_DISTANCE] = record.settings.distanceFilterMeters
        prefs[KEY_LOGGING_ACCURACY] = record.settings.accuracyFilterMeters
        prefs[KEY_LOW_ACCURACY_FALLBACK_ENABLED] = record.settings.lowAccuracyFallbackEnabled
        prefs[KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC] = record.settings.lowAccuracyFallbackTimeoutSec
        prefs[KEY_EXTENDED_PARAMS] = record.settings.sendExtendedData
        prefs[KEY_SIGNIFICANT_MOTION_ONLY] = record.settings.significantDataOnly
        prefs[KEY_AUTO_TRACKING_ENABLED] = record.settings.autoTrackingMode
        prefs[KEY_TRACKING_PROFILE] = record.settings.trackingProfile.index
        prefs[KEY_START_ON_BOOT] = record.settings.startOnBoot
        prefs[KEY_START_TRACKING_ON_LAUNCH] = record.settings.startTrackingOnLaunch
        prefs[KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP] = record.settings.keepScreenOnWhileViewingMap
    }

    private fun settingsSummary(settings: TrackerSettings): String {
        return "auto=${settings.autoTrackingMode},startOnBoot=${settings.startOnBoot},startOnLaunch=${settings.startTrackingOnLaunch},extended=${settings.sendExtendedData},sigMotion=${settings.significantDataOnly},lowAccFallback=${settings.lowAccuracyFallbackEnabled},keepScreenOn=${settings.keepScreenOnWhileViewingMap},profile=${settings.trackingProfile},interval=${settings.loggingIntervalSec},distance=${settings.distanceFilterMeters},accuracy=${settings.accuracyFilterMeters},lowAccTimeout=${settings.lowAccuracyFallbackTimeoutSec}"
    }

    private fun toRecord(prefs: Preferences): TrackerSettingsRecord {
        val defaults = TrackerSettingsDefaults.baseline
        val interval = prefs[KEY_LOGGING_INTERVAL] ?: defaults.loggingIntervalSec
        val distance = prefs[KEY_LOGGING_DISTANCE] ?: defaults.distanceFilterMeters
        val accuracy = prefs[KEY_LOGGING_ACCURACY] ?: defaults.accuracyFilterMeters
        val fallbackTimeout = prefs[KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC] ?: defaults.lowAccuracyFallbackTimeoutSec
        val profileIndex = prefs[KEY_TRACKING_PROFILE] ?: defaults.trackingProfile.index
        return TrackerSettingsRecord(
            settings = TrackerSettings(
                loggingIntervalSec = TrackerSettings.clampLoggingIntervalSec(interval),
                distanceFilterMeters = TrackerSettings.clampDistanceFilterMeters(distance),
                accuracyFilterMeters = TrackerSettings.clampAccuracyFilterMeters(accuracy),
                lowAccuracyFallbackEnabled = prefs[KEY_LOW_ACCURACY_FALLBACK_ENABLED]
                    ?: defaults.lowAccuracyFallbackEnabled,
                lowAccuracyFallbackTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                    fallbackTimeout
                ),
                sendExtendedData = prefs[KEY_EXTENDED_PARAMS] ?: defaults.sendExtendedData,
                significantDataOnly = prefs[KEY_SIGNIFICANT_MOTION_ONLY] ?: defaults.significantDataOnly,
                autoTrackingMode = prefs[KEY_AUTO_TRACKING_ENABLED] ?: defaults.autoTrackingMode,
                trackingProfile = TrackerTrackingProfile.fromIndex(profileIndex),
                startOnBoot = prefs[KEY_START_ON_BOOT] ?: defaults.startOnBoot,
                startTrackingOnLaunch = prefs[KEY_START_TRACKING_ON_LAUNCH] ?: defaults.startTrackingOnLaunch,
                keepScreenOnWhileViewingMap = prefs[KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP]
                    ?: defaults.keepScreenOnWhileViewingMap
            ),
            wasTrackingBeforeExit = prefs[KEY_WAS_TRACKING_BEFORE_EXIT] ?: false,
            schemaVersion = prefs[KEY_SCHEMA_VERSION] ?: 0
        )
    }

    companion object {
        const val DATASTORE_NAME = "tracker_settings_datastore"
        private const val TAG = "TrackerSettingsStore"

        private val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")
        private val KEY_LOGGING_INTERVAL = longPreferencesKey("logging_interval")
        private val KEY_LOGGING_DISTANCE = floatPreferencesKey("logging_distance")
        private val KEY_LOGGING_ACCURACY = floatPreferencesKey("logging_accuracy")
        private val KEY_LOW_ACCURACY_FALLBACK_ENABLED = booleanPreferencesKey(
            "low_accuracy_fallback_enabled"
        )
        private val KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC = longPreferencesKey(
            "low_accuracy_fallback_timeout_sec"
        )
        private val KEY_EXTENDED_PARAMS = booleanPreferencesKey("extended_params")
        private val KEY_SIGNIFICANT_MOTION_ONLY = booleanPreferencesKey("significant_motion_only")
        private val KEY_AUTO_TRACKING_ENABLED = booleanPreferencesKey("auto_tracking_enabled")
        private val KEY_TRACKING_PROFILE = intPreferencesKey("tracking_profile")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_START_TRACKING_ON_LAUNCH = booleanPreferencesKey("start_tracking_on_launch")
        private val KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP = booleanPreferencesKey(
            "keep_screen_on_while_viewing_map"
        )
        private val KEY_WAS_TRACKING_BEFORE_EXIT = booleanPreferencesKey("was_tracking_before_exit")
    }
}

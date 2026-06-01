package com.geovault.tracker.settings

import android.content.Context
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
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

class TrackerSettingsDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            GeoVaultCaptureLog.e(
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
            if (next == current) {
                GeoVaultCaptureLog.i(
                    TAG,
                    "datastore_write_ignored reason=$reason cause=no_op schema=${current.schemaVersion} " +
                        "wasTrackingBeforeExit=${current.wasTrackingBeforeExit} settings=${settingsSummary(current.settings)}"
                )
                return@edit
            }
            GeoVaultCaptureLog.i(
                TAG,
                "datastore_write reason=$reason schema=${next.schemaVersion} wasTrackingBeforeExit=${next.wasTrackingBeforeExit} settings=${settingsSummary(next.settings)}"
            )
            writeRecordToPrefs(prefs, next)
        }
    }

    suspend fun resetToDefaults(schemaVersion: Int) {
        GeoVaultCaptureLog.w(TAG, "datastore_reset_to_defaults schema=$schemaVersion")
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

    private fun writeRecordToPrefs(prefs: MutablePreferences, record: TrackerSettingsRecord) {
        prefs.clear()
        prefs[KEY_SCHEMA_VERSION] = record.schemaVersion
        prefs[KEY_WAS_TRACKING_BEFORE_EXIT] = record.wasTrackingBeforeExit
        prefs[KEY_LOW_ACCURACY_FALLBACK_ENABLED] = record.settings.lowAccuracyFallbackEnabled
        prefs[KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC] = record.settings.lowAccuracyFallbackTimeoutSec
        prefs[KEY_EXTENDED_PARAMS] = record.settings.sendExtendedData
        prefs[KEY_SIGNIFICANT_MOTION_ONLY] = record.settings.significantDataOnly
        prefs[KEY_SPARSE_TRACKING] = record.settings.sparseTracking
        prefs[KEY_START_ON_BOOT] = record.settings.startOnBoot
        prefs[KEY_START_TRACKING_ON_LAUNCH] = record.settings.startTrackingOnLaunch
        prefs[KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP] = record.settings.keepScreenOnWhileViewingMap
        prefs[KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS] = record.settings.groupModeFitOnlyActiveTrackers
    }

    private fun settingsSummary(settings: TrackerSettings): String {
        return "startOnBoot=${settings.startOnBoot},startOnLaunch=${settings.startTrackingOnLaunch},extended=${settings.sendExtendedData},sigMotion=${settings.significantDataOnly},sparse=${settings.sparseTracking},lowAccFallback=${settings.lowAccuracyFallbackEnabled},keepScreenOn=${settings.keepScreenOnWhileViewingMap},groupFitActiveOnly=${settings.groupModeFitOnlyActiveTrackers},lowAccTimeout=${settings.lowAccuracyFallbackTimeoutSec}"
    }

    private fun toRecord(prefs: Preferences): TrackerSettingsRecord {
        val defaults = TrackerSettingsDefaults.baseline
        val fallbackTimeout = prefs[KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC] ?: defaults.lowAccuracyFallbackTimeoutSec
        return TrackerSettingsRecord(
            settings = TrackerSettings(
                lowAccuracyFallbackEnabled = prefs[KEY_LOW_ACCURACY_FALLBACK_ENABLED]
                    ?: defaults.lowAccuracyFallbackEnabled,
                lowAccuracyFallbackTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                    fallbackTimeout
                ),
                sendExtendedData = prefs[KEY_EXTENDED_PARAMS] ?: defaults.sendExtendedData,
                significantDataOnly = prefs[KEY_SIGNIFICANT_MOTION_ONLY] ?: defaults.significantDataOnly,
                sparseTracking = prefs[KEY_SPARSE_TRACKING] ?: defaults.sparseTracking,
                startOnBoot = prefs[KEY_START_ON_BOOT] ?: defaults.startOnBoot,
                startTrackingOnLaunch = prefs[KEY_START_TRACKING_ON_LAUNCH] ?: defaults.startTrackingOnLaunch,
                keepScreenOnWhileViewingMap = prefs[KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP]
                    ?: defaults.keepScreenOnWhileViewingMap,
                groupModeFitOnlyActiveTrackers = prefs[KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS]
                    ?: defaults.groupModeFitOnlyActiveTrackers,
            ),
            wasTrackingBeforeExit = prefs[KEY_WAS_TRACKING_BEFORE_EXIT] ?: false,
            schemaVersion = prefs[KEY_SCHEMA_VERSION] ?: 0
        )
    }

    companion object {
        const val DATASTORE_NAME = "tracker_settings_datastore"
        private const val TAG = "TrackerSettingsStore"

        private val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")
        private val KEY_LOW_ACCURACY_FALLBACK_ENABLED = booleanPreferencesKey(
            "low_accuracy_fallback_enabled"
        )
        private val KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC = longPreferencesKey(
            "low_accuracy_fallback_timeout_sec"
        )
        private val KEY_EXTENDED_PARAMS = booleanPreferencesKey("extended_params")
        private val KEY_SIGNIFICANT_MOTION_ONLY = booleanPreferencesKey("significant_motion_only")
        private val KEY_SPARSE_TRACKING = booleanPreferencesKey("sparse_tracking")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_START_TRACKING_ON_LAUNCH = booleanPreferencesKey("start_tracking_on_launch")
        private val KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP = booleanPreferencesKey(
            "keep_screen_on_while_viewing_map"
        )
        private val KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS = booleanPreferencesKey(
            "group_mode_fit_only_active_trackers"
        )
        private val KEY_WAS_TRACKING_BEFORE_EXIT = booleanPreferencesKey("was_tracking_before_exit")
    }
}

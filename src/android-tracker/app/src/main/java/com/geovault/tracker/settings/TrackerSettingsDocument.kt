package com.geovault.tracker.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.Serializable

@Serializable
data class TrackerSettingsDocument(
    val startOnBoot: Boolean = false,
    val startTrackingOnLaunch: Boolean = false,
    val sendExtendedData: Boolean = true,
    val significantDataOnly: Boolean = true,
    val sparseTracking: Boolean = false,
    val lowAccuracyFallbackEnabled: Boolean = TrackerSettings.DEFAULT_LOW_ACCURACY_FALLBACK_ENABLED,
    val lowAccuracyFallbackTimeoutSec: Long = TrackerSettings.DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC,
    val keepScreenOnWhileViewingMap: Boolean = TrackerSettings.DEFAULT_KEEP_SCREEN_ON_WHILE_VIEWING_MAP,
    val groupModeFitOnlyActiveTrackers: Boolean = TrackerSettings.DEFAULT_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS,
    val wasTrackingBeforeExit: Boolean = false,
) {
    fun toSettings(): TrackerSettings = TrackerSettings(
        lowAccuracyFallbackEnabled = lowAccuracyFallbackEnabled,
        lowAccuracyFallbackTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
            lowAccuracyFallbackTimeoutSec
        ),
        sendExtendedData = sendExtendedData,
        significantDataOnly = significantDataOnly,
        sparseTracking = sparseTracking,
        startOnBoot = startOnBoot,
        startTrackingOnLaunch = startTrackingOnLaunch,
        keepScreenOnWhileViewingMap = keepScreenOnWhileViewingMap,
        groupModeFitOnlyActiveTrackers = groupModeFitOnlyActiveTrackers,
    )

    fun toRecord(): TrackerSettingsRecord = TrackerSettingsRecord(
        settings = toSettings(),
        wasTrackingBeforeExit = wasTrackingBeforeExit,
        schemaVersion = TrackerSettingsDefaults.schemaVersion,
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "tracker_settings.settings"
        const val LEGACY_DATASTORE_NAME = "tracker_settings_datastore"

        const val KEY_LOW_ACCURACY_FALLBACK_ENABLED = "low_accuracy_fallback_enabled"
        const val KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC = "low_accuracy_fallback_timeout_sec"
        const val KEY_EXTENDED_PARAMS = "extended_params"
        const val KEY_SIGNIFICANT_MOTION_ONLY = "significant_motion_only"
        const val KEY_SPARSE_TRACKING = "sparse_tracking"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_START_TRACKING_ON_LAUNCH = "start_tracking_on_launch"
        const val KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP = "keep_screen_on_while_viewing_map"
        const val KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS = "group_mode_fit_only_active_trackers"
        const val KEY_WAS_TRACKING_BEFORE_EXIT = "was_tracking_before_exit"

        fun fromSettings(
            settings: TrackerSettings,
            wasTrackingBeforeExit: Boolean,
        ): TrackerSettingsDocument = TrackerSettingsDocument(
            startOnBoot = settings.startOnBoot,
            startTrackingOnLaunch = settings.startTrackingOnLaunch,
            sendExtendedData = settings.sendExtendedData,
            significantDataOnly = settings.significantDataOnly,
            sparseTracking = settings.sparseTracking,
            lowAccuracyFallbackEnabled = settings.lowAccuracyFallbackEnabled,
            lowAccuracyFallbackTimeoutSec = settings.lowAccuracyFallbackTimeoutSec,
            keepScreenOnWhileViewingMap = settings.keepScreenOnWhileViewingMap,
            groupModeFitOnlyActiveTrackers = settings.groupModeFitOnlyActiveTrackers,
            wasTrackingBeforeExit = wasTrackingBeforeExit,
        )

        fun fromRecord(record: TrackerSettingsRecord): TrackerSettingsDocument =
            fromSettings(record.settings, record.wasTrackingBeforeExit)

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): TrackerSettingsDocument {
            val defaults = TrackerSettingsDefaults.baseline
            return TrackerSettingsDocument(
                startOnBoot = blob.boolValues[KEY_START_ON_BOOT] ?: defaults.startOnBoot,
                startTrackingOnLaunch = blob.boolValues[KEY_START_TRACKING_ON_LAUNCH]
                    ?: defaults.startTrackingOnLaunch,
                sendExtendedData = blob.boolValues[KEY_EXTENDED_PARAMS] ?: defaults.sendExtendedData,
                significantDataOnly = blob.boolValues[KEY_SIGNIFICANT_MOTION_ONLY]
                    ?: defaults.significantDataOnly,
                sparseTracking = blob.boolValues[KEY_SPARSE_TRACKING] ?: defaults.sparseTracking,
                lowAccuracyFallbackEnabled = blob.boolValues[KEY_LOW_ACCURACY_FALLBACK_ENABLED]
                    ?: defaults.lowAccuracyFallbackEnabled,
                lowAccuracyFallbackTimeoutSec = blob.longValues[KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC]
                    ?: defaults.lowAccuracyFallbackTimeoutSec,
                keepScreenOnWhileViewingMap = blob.boolValues[KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP]
                    ?: defaults.keepScreenOnWhileViewingMap,
                groupModeFitOnlyActiveTrackers = blob.boolValues[KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS]
                    ?: defaults.groupModeFitOnlyActiveTrackers,
                wasTrackingBeforeExit = blob.boolValues[KEY_WAS_TRACKING_BEFORE_EXIT] ?: false,
            )
        }

        fun fromLegacyPreferences(prefs: Preferences): TrackerSettingsDocument {
            val defaults = TrackerSettingsDefaults.baseline
            val timeout = prefs[longPreferencesKey(KEY_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC)]
                ?: defaults.lowAccuracyFallbackTimeoutSec
            return TrackerSettingsDocument(
                startOnBoot = prefs[booleanPreferencesKey(KEY_START_ON_BOOT)] ?: defaults.startOnBoot,
                startTrackingOnLaunch = prefs[booleanPreferencesKey(KEY_START_TRACKING_ON_LAUNCH)]
                    ?: defaults.startTrackingOnLaunch,
                sendExtendedData = prefs[booleanPreferencesKey(KEY_EXTENDED_PARAMS)]
                    ?: defaults.sendExtendedData,
                significantDataOnly = prefs[booleanPreferencesKey(KEY_SIGNIFICANT_MOTION_ONLY)]
                    ?: defaults.significantDataOnly,
                sparseTracking = prefs[booleanPreferencesKey(KEY_SPARSE_TRACKING)]
                    ?: defaults.sparseTracking,
                lowAccuracyFallbackEnabled = prefs[booleanPreferencesKey(KEY_LOW_ACCURACY_FALLBACK_ENABLED)]
                    ?: defaults.lowAccuracyFallbackEnabled,
                lowAccuracyFallbackTimeoutSec = timeout,
                keepScreenOnWhileViewingMap = prefs[booleanPreferencesKey(KEY_KEEP_SCREEN_ON_WHILE_VIEWING_MAP)]
                    ?: defaults.keepScreenOnWhileViewingMap,
                groupModeFitOnlyActiveTrackers = prefs[booleanPreferencesKey(KEY_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS)]
                    ?: defaults.groupModeFitOnlyActiveTrackers,
                wasTrackingBeforeExit = prefs[booleanPreferencesKey(KEY_WAS_TRACKING_BEFORE_EXIT)] ?: false,
            )
        }
    }
}

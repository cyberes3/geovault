package com.geovault.tracker.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class TrackerSettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : TrackerSettingsRepository {

    companion object {
        const val PREFS_NAME = "geovault_prefs"

        const val KEY_LOGGING_INTERVAL = "logging_interval"
        const val KEY_LOGGING_DISTANCE = "logging_distance"
        const val KEY_LOGGING_ACCURACY = "logging_accuracy"
        const val KEY_EXTENDED_PARAMS = "extended_params"
        const val KEY_SIGNIFICANT_MOTION_ONLY = "significant_motion_only"
        const val KEY_AUTO_TRACKING_ENABLED = "auto_tracking_enabled"
        const val KEY_TRACKING_PROFILE = "tracking_profile"
        const val KEY_WAS_TRACKING_BEFORE_EXIT = "was_tracking_before_exit"
        const val KEY_RESTART_TRACKING_IF_KILLED = "restart_tracking_if_killed"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_START_TRACKING_ON_LAUNCH = "start_tracking_on_launch"
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val settingsState = MutableStateFlow(readSettingsFromPrefs())

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in trackedSettingsKeys) {
            settingsState.value = readSettingsFromPrefs()
        }
    }

    private val trackedSettingsKeys = setOf(
        KEY_LOGGING_INTERVAL,
        KEY_LOGGING_DISTANCE,
        KEY_LOGGING_ACCURACY,
        KEY_EXTENDED_PARAMS,
        KEY_SIGNIFICANT_MOTION_ONLY,
        KEY_AUTO_TRACKING_ENABLED,
        KEY_TRACKING_PROFILE,
        KEY_RESTART_TRACKING_IF_KILLED,
        KEY_START_ON_BOOT,
        KEY_START_TRACKING_ON_LAUNCH
    )

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun getSettings(): TrackerSettings = settingsState.value

    override fun observeSettings(): Flow<TrackerSettings> = settingsState.asStateFlow()

    override fun setSendExtendedData(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTENDED_PARAMS, enabled).apply()
    }

    override fun setSignificantDataOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIGNIFICANT_MOTION_ONLY, enabled).apply()
    }

    override fun setResetTrackingIfKilled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESTART_TRACKING_IF_KILLED, enabled).apply()
    }

    override fun setAutoTrackingMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRACKING_ENABLED, enabled).apply()
    }

    override fun setTrackingProfile(profile: TrackerTrackingProfile) {
        val editor = prefs.edit()
            .putString(KEY_TRACKING_PROFILE, profile.index.toString())
        when (profile) {
            TrackerTrackingProfile.WALKING -> {
                editor
                    .putString(KEY_LOGGING_INTERVAL, "30")
                    .putString(KEY_LOGGING_DISTANCE, "10")
                    .putString(KEY_LOGGING_ACCURACY, "50")
            }
            TrackerTrackingProfile.BIKING -> {
                editor
                    .putString(KEY_LOGGING_INTERVAL, "15")
                    .putString(KEY_LOGGING_DISTANCE, "30")
                    .putString(KEY_LOGGING_ACCURACY, "100")
            }
            TrackerTrackingProfile.DRIVING -> {
                editor
                    .putString(KEY_LOGGING_INTERVAL, "10")
                    .putString(KEY_LOGGING_DISTANCE, "100")
                    .putString(KEY_LOGGING_ACCURACY, "200")
            }
            TrackerTrackingProfile.CUSTOM -> {
                // Preserve custom numeric values when switching to Custom.
            }
        }
        editor.apply()
    }

    override fun setLoggingIntervalSec(value: Long) {
        val clamped = TrackerSettings.clampLoggingIntervalSec(value)
        prefs.edit().putString(KEY_LOGGING_INTERVAL, clamped.toString()).apply()
    }

    override fun setDistanceFilterMeters(value: Float) {
        val clamped = TrackerSettings.clampDistanceFilterMeters(value)
        prefs.edit().putString(KEY_LOGGING_DISTANCE, clamped.toString()).apply()
    }

    override fun setAccuracyFilterMeters(value: Float) {
        val clamped = TrackerSettings.clampAccuracyFilterMeters(value)
        prefs.edit().putString(KEY_LOGGING_ACCURACY, clamped.toString()).apply()
    }

    override fun setStartOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    override fun setStartTrackingOnLaunch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_TRACKING_ON_LAUNCH, enabled).apply()
    }

    override fun wasTrackingBeforeExit(): Boolean {
        return prefs.getBoolean(KEY_WAS_TRACKING_BEFORE_EXIT, false)
    }

    override fun setWasTrackingBeforeExit(value: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_TRACKING_BEFORE_EXIT, value).apply()
    }

    override fun clearWasTrackingBeforeExit() {
        prefs.edit().remove(KEY_WAS_TRACKING_BEFORE_EXIT).commit()
    }

    private fun readSettingsFromPrefs(): TrackerSettings {
        val intervalSecRaw = prefs.getString(
            KEY_LOGGING_INTERVAL,
            TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC.toString()
        )?.toLongOrNull() ?: TrackerSettings.DEFAULT_LOGGING_INTERVAL_SEC
        val distanceRaw = prefs.getString(
            KEY_LOGGING_DISTANCE,
            TrackerSettings.DEFAULT_DISTANCE_FILTER_METERS.toString()
        )?.toFloatOrNull() ?: TrackerSettings.DEFAULT_DISTANCE_FILTER_METERS
        val accuracyRaw = prefs.getString(
            KEY_LOGGING_ACCURACY,
            TrackerSettings.DEFAULT_ACCURACY_FILTER_METERS.toString()
        )?.toFloatOrNull() ?: TrackerSettings.DEFAULT_ACCURACY_FILTER_METERS
        val profileIndex = prefs.getString(KEY_TRACKING_PROFILE, "1")?.toIntOrNull() ?: 1

        return TrackerSettings(
            loggingIntervalSec = TrackerSettings.clampLoggingIntervalSec(intervalSecRaw),
            distanceFilterMeters = TrackerSettings.clampDistanceFilterMeters(distanceRaw),
            accuracyFilterMeters = TrackerSettings.clampAccuracyFilterMeters(accuracyRaw),
            sendExtendedData = prefs.getBoolean(KEY_EXTENDED_PARAMS, true),
            significantDataOnly = prefs.getBoolean(KEY_SIGNIFICANT_MOTION_ONLY, true),
            resetTrackingIfKilled = prefs.getBoolean(KEY_RESTART_TRACKING_IF_KILLED, true),
            autoTrackingMode = prefs.getBoolean(KEY_AUTO_TRACKING_ENABLED, true),
            trackingProfile = TrackerTrackingProfile.fromIndex(profileIndex),
            startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false),
            startTrackingOnLaunch = prefs.getBoolean(KEY_START_TRACKING_ON_LAUNCH, false)
        )
    }
}

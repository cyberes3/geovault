package com.geovault.tracker.settings

import kotlinx.coroutines.flow.Flow

interface TrackerSettingsRepository {
    fun getSettings(): TrackerSettings
    fun observeSettings(): Flow<TrackerSettings>

    fun setSendExtendedData(enabled: Boolean)
    fun setSignificantDataOnly(enabled: Boolean)
    fun setResetTrackingIfKilled(enabled: Boolean)
    fun setAutoTrackingMode(enabled: Boolean)
    fun setTrackingProfile(profile: TrackerTrackingProfile)
    fun setLoggingIntervalSec(value: Long)
    fun setDistanceFilterMeters(value: Float)
    fun setAccuracyFilterMeters(value: Float)
    fun setLowAccuracyFallbackEnabled(enabled: Boolean)
    fun setLowAccuracyFallbackTimeoutSec(value: Long)
    fun setStartOnBoot(enabled: Boolean)
    fun setStartTrackingOnLaunch(enabled: Boolean)

    fun wasTrackingBeforeExit(): Boolean
    fun setWasTrackingBeforeExit(value: Boolean)
    fun clearWasTrackingBeforeExit()
}

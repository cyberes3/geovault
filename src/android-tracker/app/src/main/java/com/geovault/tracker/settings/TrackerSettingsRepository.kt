package com.geovault.tracker.settings

import kotlinx.coroutines.flow.Flow

interface TrackerSettingsRepository {
    fun isReady(): Boolean
    fun getState(): TrackerSettingsState
    fun observeState(): Flow<TrackerSettingsState>
    fun getSettings(): TrackerSettings
    fun observeSettings(): Flow<TrackerSettings>
    fun dumpDebugState(reason: String = "manual")

    fun setSendExtendedData(enabled: Boolean)
    fun setSignificantDataOnly(enabled: Boolean)
    fun setAutoTrackingMode(enabled: Boolean)
    fun setTrackingProfile(profile: TrackerTrackingProfile)
    fun setLoggingIntervalSec(value: Long)
    fun setDistanceFilterMeters(value: Float)
    fun setAccuracyFilterMeters(value: Float)
    fun setLowAccuracyFallbackEnabled(enabled: Boolean)
    fun setLowAccuracyFallbackTimeoutSec(value: Long)
    fun setStartOnBoot(enabled: Boolean)
    fun setStartTrackingOnLaunch(enabled: Boolean)
    fun setKeepScreenOnWhileViewingMap(enabled: Boolean)

    fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean)

    fun wasTrackingBeforeExit(): Boolean
    fun setWasTrackingBeforeExit(value: Boolean)
    fun clearWasTrackingBeforeExit()
}

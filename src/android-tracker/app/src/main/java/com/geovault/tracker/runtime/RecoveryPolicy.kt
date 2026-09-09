package com.geovault.tracker.runtime

import android.location.LocationManager
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.tracking.TrackingServiceIntents

sealed class RecoveryDecision {
    data object Arm : RecoveryDecision()
    data class AttemptStart(
        val trigger: RuntimeTrigger,
        val reason: String,
    ) : RecoveryDecision()
    data class Defer(
        val delayMs: Long,
        val reason: String,
    ) : RecoveryDecision()
    data class Abandon(
        val reason: String,
    ) : RecoveryDecision()
}

enum class RecoverySource {
    ColdStart,
    Boot,
    WatchdogTick,
}

data class RecoveryInput(
    val source: RecoverySource,
    val shouldBeRunning: Boolean,
    val restartTrackingIfKilled: Boolean,
    val startOnBoot: Boolean,
    val serviceRunning: Boolean,
    val settingsLoadState: TrackerSettingsLoadState,
    val userUnlocked: Boolean,
    val hasRequiredPermissions: Boolean,
    val gpsProviderEnabled: Boolean,
    val selectedTrackerId: String,
    val lastHeartbeatAtMs: Long,
    val nowMs: Long,
)

object RecoveryPolicy {
    const val LOADING_RETRY_MS = 5_000L
    const val SETTINGS_ERROR_RETRY_MS = 30_000L
    const val HEARTBEAT_STALE_MS = 30_000L

    fun decide(input: RecoveryInput): RecoveryDecision {
        return when (input.source) {
            RecoverySource.ColdStart -> decideColdStart(input)
            RecoverySource.Boot -> decideBoot(input)
            RecoverySource.WatchdogTick -> decideWatchdog(input)
        }
    }

    private fun decideColdStart(input: RecoveryInput): RecoveryDecision {
        if (input.serviceRunning) return RecoveryDecision.Abandon("service_already_running")
        if (!input.shouldBeRunning) return RecoveryDecision.Abandon("not_desired")
        return when (input.settingsLoadState) {
            TrackerSettingsLoadState.Loading -> RecoveryDecision.Defer(LOADING_RETRY_MS, "settings_loading")
            TrackerSettingsLoadState.Error -> RecoveryDecision.Defer(SETTINGS_ERROR_RETRY_MS, "settings_error")
            TrackerSettingsLoadState.Ready -> RecoveryDecision.Arm
        }
    }

    private fun decideBoot(input: RecoveryInput): RecoveryDecision {
        if (!input.userUnlocked) return RecoveryDecision.Defer(LOADING_RETRY_MS, "user_locked")
        if (!input.startOnBoot && !input.shouldBeRunning) {
            return RecoveryDecision.Abandon("start_on_boot_disabled")
        }
        if (!input.hasRequiredPermissions) return RecoveryDecision.Abandon("permissions_missing")
        if (!input.gpsProviderEnabled) return RecoveryDecision.Abandon("gps_disabled")
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(input.selectedTrackerId)) {
            return RecoveryDecision.Abandon("invalid_tracker")
        }
        if (input.serviceRunning) return RecoveryDecision.Abandon("service_already_running")
        return RecoveryDecision.AttemptStart(RuntimeTrigger.BOOT, "boot")
    }

    private fun decideWatchdog(input: RecoveryInput): RecoveryDecision {
        if (!input.restartTrackingIfKilled || !input.shouldBeRunning) {
            return RecoveryDecision.Abandon("watchdog_disabled")
        }
        return when (input.settingsLoadState) {
            TrackerSettingsLoadState.Loading -> RecoveryDecision.Defer(LOADING_RETRY_MS, "settings_loading")
            TrackerSettingsLoadState.Error -> RecoveryDecision.Defer(SETTINGS_ERROR_RETRY_MS, "settings_error")
            TrackerSettingsLoadState.Ready -> decideWatchdogReady(input)
        }
    }

    private fun decideWatchdogReady(input: RecoveryInput): RecoveryDecision {
        if (!input.hasRequiredPermissions) return RecoveryDecision.Abandon("permissions_missing")
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(input.selectedTrackerId)) {
            return RecoveryDecision.Abandon("invalid_tracker")
        }
        if (!input.gpsProviderEnabled) return RecoveryDecision.Abandon("gps_disabled")
        val stale = input.lastHeartbeatAtMs <= 0L ||
            (input.nowMs - input.lastHeartbeatAtMs) > HEARTBEAT_STALE_MS
        if (!stale) return RecoveryDecision.Abandon("heartbeat_fresh")
        if (input.serviceRunning) return RecoveryDecision.Abandon("heartbeat_stale_service_alive")
        return RecoveryDecision.AttemptStart(RuntimeTrigger.WATCHDOG_TICK, "heartbeat_stale")
    }

    fun isGpsProviderEnabled(locationManager: LocationManager?): Boolean {
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
}

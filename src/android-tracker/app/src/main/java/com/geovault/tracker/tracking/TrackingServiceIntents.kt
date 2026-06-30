package com.geovault.tracker.tracking

import android.content.Context
import android.content.Intent
import android.location.Location
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.runtime.RuntimeTrigger

object TrackingServiceIntents {
    const val ACTION_START = "com.geovault.tracker.ACTION_START"
    const val ACTION_STOP = "com.geovault.tracker.ACTION_STOP"
    const val ACTION_RESHOW_FOREGROUND = "com.geovault.tracker.ACTION_RESHOW_FOREGROUND"
    const val ACTION_SEND_MANUAL_POINT = "com.geovault.tracker.ACTION_SEND_MANUAL_POINT"
    const val ACTION_LOCATION_UPDATE = "com.geovault.tracker.ACTION_LOCATION_UPDATE"
    const val ACTION_STATIONARY_PING_DUE = "com.geovault.tracker.ACTION_STATIONARY_PING_DUE"
    const val EXTRA_FOREGROUND_SERVICE_START_REQUIRED = "extra_foreground_service_start_required"
    const val EXTRA_BACKGROUND_WAKEUP_SOURCE = "extra_background_wakeup_source"
    const val ACTION_TRACKING_ERROR = "com.geovault.tracker.ACTION_TRACKING_ERROR"
    const val EXTRA_TRACKING_ERROR_MESSAGE = "extra_tracking_error_message"
    const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.TRACKING_NOTIFICATION_DISMISSED"

    const val EXTRAS_KEY_PAUSED_FRESHNESS = PausedFreshnessPointFactory.EXTRAS_KEY_PAUSED_FRESHNESS
    const val EXTRAS_KEY_PAUSED_FRESHNESS_SOURCE_PROVIDER =
        PausedFreshnessPointFactory.EXTRAS_KEY_SOURCE_PROVIDER

    @JvmStatic
    fun shouldRestartTrackingAfterProcessDeath(): Boolean = false

    enum class StartupCommandPath {
        StartTracking,
        StopNoRestart,
        ReshowForeground,
        ManualSendPoint,
        LocationUpdate,
        StationaryPingDue,
        StopUnknown,
    }

    @JvmStatic
    fun resolveStartupCommandPath(action: String?): StartupCommandPath {
        return when (action) {
            ACTION_START -> StartupCommandPath.StartTracking
            ACTION_STOP -> StartupCommandPath.StopUnknown
            ACTION_RESHOW_FOREGROUND -> StartupCommandPath.ReshowForeground
            ACTION_SEND_MANUAL_POINT -> StartupCommandPath.ManualSendPoint
            ACTION_LOCATION_UPDATE -> StartupCommandPath.LocationUpdate
            ACTION_STATIONARY_PING_DUE -> StartupCommandPath.StationaryPingDue
            null -> {
                if (shouldRestartTrackingAfterProcessDeath()) {
                    StartupCommandPath.StartTracking
                } else {
                    StartupCommandPath.StopNoRestart
                }
            }
            else -> StartupCommandPath.StopUnknown
        }
    }

    @JvmStatic
    fun requiresForegroundPromotion(path: StartupCommandPath): Boolean {
        return requiresForegroundPromotion(path, foregroundStartRequired = false)
    }

    @JvmStatic
    fun requiresForegroundPromotion(
        path: StartupCommandPath,
        foregroundStartRequired: Boolean,
    ): Boolean {
        if (path == StartupCommandPath.StartTracking) return true
        if (!foregroundStartRequired) return false
        return when (path) {
            StartupCommandPath.LocationUpdate -> true
            StartupCommandPath.StartTracking,
            StartupCommandPath.StopNoRestart,
            StartupCommandPath.ReshowForeground,
            StartupCommandPath.ManualSendPoint,
            StartupCommandPath.StationaryPingDue,
            StartupCommandPath.StopUnknown -> false
        }
    }

    @JvmStatic
    fun resolveStartupTrigger(action: String?): String {
        return when (action) {
            ACTION_START -> "explicit_start"
            ACTION_STOP -> "explicit_stop"
            ACTION_RESHOW_FOREGROUND -> "reshow_foreground"
            ACTION_SEND_MANUAL_POINT -> "manual_send_point"
            ACTION_LOCATION_UPDATE -> "location_update"
            ACTION_STATIONARY_PING_DUE -> "stationary_ping_alarm"
            null -> "process_restart"
            else -> "unknown_action"
        }
    }

    @JvmStatic
    fun mapRuntimeTrigger(trigger: String): RuntimeTrigger {
        return when (trigger) {
            "explicit_start" -> RuntimeTrigger.EXPLICIT_START
            "process_restart" -> RuntimeTrigger.PROCESS_RESTART
            "watchdog_tick" -> RuntimeTrigger.WATCHDOG_TICK
            "main_resume_after_kill" -> RuntimeTrigger.MAIN_RESUME_AFTER_KILL
            "main_start_on_launch" -> RuntimeTrigger.MAIN_START_ON_LAUNCH
            else -> RuntimeTrigger.UNKNOWN
        }
    }

    @JvmStatic
    fun buildLocationUpdateIntent(context: Context, locations: List<Location>): Intent {
        val appContext = context.applicationContext
        return Intent(appContext, TrackingService::class.java).apply {
            action = ACTION_LOCATION_UPDATE
            setPackage(appContext.packageName)
            putParcelableArrayListExtra(
                TrackingServiceConstants.EXTRA_LOCATION_UPDATES,
                ArrayList(locations.map { Location(it) }),
            )
        }
    }

    fun extractLocationUpdateIntentLocations(intent: Intent?): List<Location> {
        if (intent?.action != ACTION_LOCATION_UPDATE) return emptyList()
        return intent.getParcelableArrayListExtra(TrackingServiceConstants.EXTRA_LOCATION_UPDATES, Location::class.java)
            ?.map { Location(it) }
            .orEmpty()
    }

    @JvmStatic
    fun hasValidSelectedTrackerId(selectedTrackerId: String): Boolean {
        if (selectedTrackerId.isBlank()) return false
        return try {
            java.util.UUID.fromString(selectedTrackerId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

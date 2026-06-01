package com.geovault.tracker.startup

import android.content.Intent
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.tracking.TrackingServiceConstants

data class BootStartupSnapshot(
    val action: String?,
    val startOnBoot: Boolean,
    val wasTrackingBeforeExit: Boolean,
    val userUnlocked: Boolean,
    val hasRequiredPermissions: Boolean,
    val gpsProviderEnabled: Boolean,
    val selectedTrackerId: String
)

enum class BootStartupBlocker(val logLabel: String) {
    UnsupportedAction("unsupported_action"),
    StartOnBootDisabled("start_on_boot_disabled"),
    UserLocked("user_locked"),
    MissingTrackingPermissions("missing_tracking_permissions"),
    GpsProviderDisabled("gps_provider_disabled"),
    InvalidSelectedTracker("invalid_selected_tracker")
}

data class BootStartupDecision(
    val shouldStartTracking: Boolean,
    val blockers: List<BootStartupBlocker>
)

object BootStartupPolicy {
    private val supportedActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED
    )

    fun evaluate(snapshot: BootStartupSnapshot): BootStartupDecision {
        val blockers = mutableListOf<BootStartupBlocker>()
        if (!supportedActions.contains(snapshot.action)) {
            blockers += BootStartupBlocker.UnsupportedAction
        }
        if (!snapshot.startOnBoot && !snapshot.wasTrackingBeforeExit) {
            blockers += BootStartupBlocker.StartOnBootDisabled
        }
        if (!snapshot.userUnlocked) {
            blockers += BootStartupBlocker.UserLocked
        }
        if (!snapshot.hasRequiredPermissions) {
            blockers += BootStartupBlocker.MissingTrackingPermissions
        }
        if (!snapshot.gpsProviderEnabled) {
            blockers += BootStartupBlocker.GpsProviderDisabled
        }
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(snapshot.selectedTrackerId)) {
            blockers += BootStartupBlocker.InvalidSelectedTracker
        }
        return BootStartupDecision(
            shouldStartTracking = blockers.isEmpty(),
            blockers = blockers
        )
    }
}

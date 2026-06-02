package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.Toast
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ForegroundSubsystem(private val rt: PositioningRuntime) {
    fun failStartup(message: String, path: TrackingServiceIntents.StartupCommandPath, trigger: String, reason: String) {
        GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Tracking start failed: $reason path=$path trigger=$trigger")
        TrackPointBus.resumeLocalDelivery()
        rt.projection.transitionControlState(TrackingControlEvent.StartFailed, failureReason = message)
        rt.lifecycle.transitionToStoppedState(failureReason = message)
        rt.deps.settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(rt.ports.service.applicationContext, reason = "startup_failed")
        rt.deps.runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.STARTUP_FAILED,
            reason = reason,
            trigger = TrackingServiceIntents.mapRuntimeTrigger(trigger)
        )
        rt.serviceScope.launch(Dispatchers.Main) {
            rt.ports.service.sendBroadcast(
                Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                    setPackage(rt.ports.service.packageName)
                    putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(rt.ports.service, message, Toast.LENGTH_LONG).show()
        }
        rt.foreground.stopSelfSafelyAfterStartup(reason = "startup_failed")
    }

    fun failActiveTrackingAndStop(message: String) {
        rt.projection.transitionControlState(TrackingControlEvent.FatalFailure, failureReason = message)
        rt.serviceScope.launch(Dispatchers.Main) {
            rt.ports.service.sendBroadcast(
                Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                    setPackage(rt.ports.service.packageName)
                    putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(rt.ports.service, message, Toast.LENGTH_LONG).show()
        }
        rt.lifecycle.stopTracking(reason = "fatal_failure", failureReason = message)
    }

    fun promoteToForegroundForStartup(
        trigger: String,
        action: String?,
        path: TrackingServiceIntents.StartupCommandPath
    ): Boolean {
        if (rt.state.startupForegroundPromoted) return true
        return try {
            rt.ports.startForeground(
                rt.deps.notificationPresenter.buildTrackingNotification(rt.state.runtimeSnapshot),
            )
            rt.state.startupForegroundPromoted = true
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Foreground promotion succeeded trigger=$trigger")
            rt.foreground.logNotificationSurfaceDiagnostics(
                trigger = trigger,
                action = action,
                path = path,
                stage = "foreground_promoted"
            )
            true
        } catch (e: Exception) {
            if (e is ForegroundServiceStartNotAllowedException) {
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Foreground start not allowed for trigger=$trigger", e)
            } else {
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Foreground promotion failed for trigger=$trigger", e)
            }
            if (path == TrackingServiceIntents.StartupCommandPath.StartTracking) {
                TrackingRecoveryCoordinator.markIntentionalStop(
                    rt.ports.service.applicationContext,
                    reason = "fgs_start_failed_$trigger"
                )
            } else {
                rt.deps.runtimeTelemetry.decision(
                    "foreground_promotion_failed",
                    "trigger=$trigger path=$path action=${action ?: "none"} " +
                        "error=${e.javaClass.simpleName}:${e.message ?: "none"}"
                )
                TrackingRecoveryCoordinator.ensureWatchdogScheduled(rt.ports.service.applicationContext)
            }
            rt.foreground.logNotificationSurfaceDiagnostics(
                trigger = trigger,
                action = action,
                path = path,
                stage = "foreground_promotion_failed"
            )
            false
        }
    }

    fun stopSelfSafelyAfterStartup(reason: String) {
        if (rt.lifecycle.isTrackingActiveOrStarting()) {
            rt.lifecycle.transitionToStoppedState(failureReason = reason)
        } else {
            SessionResetCoordinator(rt).applyForStop()
        }
        rt.lifecycle.cleanupServiceResources(reason = reason)
        rt.lifecycle.stopServiceInstance(reason = reason)
    }

    fun logNotificationSurfaceDiagnostics(
        trigger: String,
        action: String?,
        path: TrackingServiceIntents.StartupCommandPath,
        stage: String
    ) {
        val notificationManager = rt.ports.service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val keyguardManager = rt.ports.service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val userManager = rt.ports.service.getSystemService(Context.USER_SERVICE) as? UserManager
        val channel = notificationManager?.getNotificationChannel(TrackingServiceConstants.CHANNEL_ID)
        val activeNotificationIds = runCatching {
            notificationManager?.activeNotifications?.map { it.id } ?: emptyList()
        }.getOrElse { emptyList() }
        val appImportance = runCatching { notificationManager?.importance }.getOrNull()
        GeoVaultCaptureLog.i(
            TrackingServiceConstants.TAG,
            "Notification diagnostics stage=$stage trigger=$trigger action=$action path=$path " +
                "notificationsEnabled=${notificationManager?.areNotificationsEnabled()} appImportance=$appImportance " +
                "channelExists=${channel != null} channelImportance=${channel?.importance} " +
                "channelLockscreenVisibility=${channel?.lockscreenVisibility} " +
                "channelBypassDnd=${channel?.canBypassDnd()} channelShowBadge=${channel?.canShowBadge()} " +
                "activeNotificationIds=$activeNotificationIds " +
                "keyguardLocked=${keyguardManager?.isKeyguardLocked} " +
                "deviceLocked=${keyguardManager?.isDeviceLocked} userUnlocked=${userManager?.isUserUnlocked}"
        )
    }

}

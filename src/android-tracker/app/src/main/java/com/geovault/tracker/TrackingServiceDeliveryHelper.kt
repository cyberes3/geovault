package com.geovault.tracker

import android.content.Context
import android.content.Intent
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.core.content.ContextCompat
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.tracking.TrackingServiceIntents

internal enum class TrackingServiceDeliverySource(val logName: String) {
    FusedLocationUpdate("fused_location_update"),
}

internal sealed class TrackingServiceDeliveryResult {
    data class Started(
        val source: TrackingServiceDeliverySource,
        val foregroundEscalated: Boolean,
    ) : TrackingServiceDeliveryResult()

    data class Failed(
        val source: TrackingServiceDeliverySource,
        val reason: String,
    ) : TrackingServiceDeliveryResult()
}

internal interface TrackingServiceStarter {
    fun startService(context: Context, intent: Intent)
    fun startForegroundService(context: Context, intent: Intent)
}

internal object AndroidTrackingServiceStarter : TrackingServiceStarter {
    override fun startService(context: Context, intent: Intent) {
        context.startService(intent)
    }

    override fun startForegroundService(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Delivers tracking wakeups without letting Android background-start rules silently drop data.
 *
 * Receivers first try the cheap `startService` path. If Android rejects that because the app is
 * backgrounded, we escalate to `startForegroundService` only while runtime state says tracking is
 * active or starting. That keeps stale PendingIntent deliveries from bootstrapping an unnecessary
 * location foreground service after tracking has already stopped.
 */
internal object TrackingServiceDeliveryHelper {
    private const val TAG = "TrackingServiceDelivery"

    fun deliver(
        context: Context,
        intent: Intent,
        source: TrackingServiceDeliverySource,
        starter: TrackingServiceStarter = AndroidTrackingServiceStarter,
        runtimeSnapshot: TrackingRuntimeSnapshot = TrackingRuntimeStateStore.state.value,
    ): TrackingServiceDeliveryResult {
        val appContext = context.applicationContext
        val telemetry = RuntimeTelemetry(appContext)
        val serviceIntent = Intent(intent).apply {
            setPackage(appContext.packageName)
        }
        val action = serviceIntent.action ?: "none"
        return try {
            if (CaptureLogThrottle.shouldLogInterval("tracking_service_delivery_success", 60_000L)) {
                GeoVaultCaptureLog.d(TAG, "deliver source=${source.logName} action=$action path=startService")
                telemetry.decision(
                    name = "tracking_service_delivery",
                    details = "source=${source.logName} action=$action path=startService result=started",
                )
            }
            starter.startService(appContext, serviceIntent)
            TrackingServiceDeliveryResult.Started(source, foregroundEscalated = false)
        } catch (startRejected: IllegalStateException) {
            GeoVaultCaptureLog.w(
                TAG,
                "startService rejected source=${source.logName} action=$action; evaluating FGS escalation",
                startRejected
            )
            if (!runtimeSnapshot.isTrackingActiveOrStarting()) {
                val reason = "inactive_runtime_start_rejected"
                recordDeliveryFailure(
                    context = appContext,
                    telemetry = telemetry,
                    source = source,
                    action = action,
                    reason = reason,
                    error = startRejected,
                )
                return TrackingServiceDeliveryResult.Failed(source, reason)
            }
            deliverViaForegroundService(
                context = appContext,
                originalIntent = serviceIntent,
                source = source,
                action = action,
                starter = starter,
                telemetry = telemetry,
            )
        } catch (security: SecurityException) {
            val reason = "start_service_security_exception"
            recordDeliveryFailure(
                context = appContext,
                telemetry = telemetry,
                source = source,
                action = action,
                reason = reason,
                error = security,
            )
            TrackingServiceDeliveryResult.Failed(source, reason)
        }
    }

    private fun deliverViaForegroundService(
        context: Context,
        originalIntent: Intent,
        source: TrackingServiceDeliverySource,
        action: String,
        starter: TrackingServiceStarter,
        telemetry: RuntimeTelemetry,
    ): TrackingServiceDeliveryResult {
        val foregroundIntent = Intent(originalIntent).apply {
            putExtra(TrackingServiceIntents.EXTRA_FOREGROUND_SERVICE_START_REQUIRED, true)
            putExtra(TrackingServiceIntents.EXTRA_BACKGROUND_WAKEUP_SOURCE, source.logName)
        }
        return try {
            GeoVaultCaptureLog.i(TAG, "deliver source=${source.logName} action=$action path=startForegroundService")
            starter.startForegroundService(context, foregroundIntent)
            telemetry.decision(
                "tracking_service_delivery",
                "source=${source.logName} action=$action path=startForegroundService result=started"
            )
            TrackingServiceDeliveryResult.Started(source, foregroundEscalated = true)
        } catch (error: Exception) {
            val reason = "foreground_service_start_failed"
            recordDeliveryFailure(
                context = context,
                telemetry = telemetry,
                source = source,
                action = action,
                reason = reason,
                error = error,
            )
            TrackingServiceDeliveryResult.Failed(source, reason)
        }
    }

    private fun recordDeliveryFailure(
        context: Context,
        telemetry: RuntimeTelemetry,
        source: TrackingServiceDeliverySource,
        action: String,
        reason: String,
        error: Throwable,
    ) {
        GeoVaultCaptureLog.e(TAG, "delivery failed source=${source.logName} action=$action reason=$reason", error)
        telemetry.decision(
            "tracking_service_delivery",
            "source=${source.logName} action=$action result=failed reason=$reason " +
                "error=${error.javaClass.simpleName}:${error.message ?: "none"}"
        )
        TrackingRecoveryCoordinator.ensureWatchdogScheduled(context)
    }

    private fun TrackingRuntimeSnapshot.isTrackingActiveOrStarting(): Boolean {
        return sessionActive || startupActive || isRunning
    }
}

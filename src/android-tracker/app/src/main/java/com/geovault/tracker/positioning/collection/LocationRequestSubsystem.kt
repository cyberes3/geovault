package com.geovault.tracker.positioning.collection
import com.geovault.tracker.positioning.LocationRequestController
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.R
import com.geovault.tracker.location.TrackingLocationRequestInput
import com.geovault.tracker.location.TrackingLocationRequestPolicy
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.positioning.LocationRequestKey
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LocationRequestSubsystem(private val rt: PositioningRuntime) {
    fun applyCurrentLocationRequest(reason: String): Boolean {
        if (!rt.state.isTracking) return false
        if (
            rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return false
        }
        if (!TrackingPermissionGate.hasLocationPermission(rt.ports.service)) return false
        val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext()
        val intervalSec = runtimeContext.locationIntervalSec
        val distanceFilter = runtimeContext.distanceFilterMeters
        val requestKey = LocationRequestKey(
            intervalSec = intervalSec,
            distanceFilterMeters = distanceFilter,
            fastLock = rt.state.isFastGpsLockWindowActive,
        )
        if (rt.state.lastAppliedLocationRequestKey == requestKey) {
            rt.deps.runtimeTelemetry.decision(
                name = "location_request_unchanged",
                details = "reason=$reason intervalSec=$intervalSec distance=$distanceFilter fastLock=${rt.state.isFastGpsLockWindowActive}"
            )
            rt.locationRequests.startFixDeliveryWatchdog()
            return true
        }
        val request = if (rt.state.isFastGpsLockWindowActive) {
            TrackingLocationRequestPolicy.buildFastLockRequest()
        } else {
            TrackingLocationRequestPolicy.buildNormalRequest(
                TrackingLocationRequestInput(
                    intervalSec = intervalSec,
                    distanceFilterMeters = distanceFilter
                )
            )
        }
        return try {
            val started = rt.deps.locationSessionCoordinator.startSession(request = request)
            if (!started) return false
            rt.state.lastAppliedLocationRequestKey = requestKey
            rt.state.lastLocationRequestAppliedAtMs = System.currentTimeMillis()
            rt.deps.providerHealthController.markRequestApplied(rt.state.lastLocationRequestAppliedAtMs)
            rt.state.locationRequestReapplyRetryJob?.cancel()
            rt.state.locationRequestReapplyRetryJob = null
            rt.deps.runtimeTelemetry.decision(
                name = "location_request_applied",
                details = "reason=$reason intervalSec=$intervalSec distance=$distanceFilter fastLock=${rt.state.isFastGpsLockWindowActive}"
            )
            rt.locationRequests.startFixDeliveryWatchdog()
            true
        } catch (security: SecurityException) {
            GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Location request failed reason=$reason", security)
            false
        }
    }

    fun reapplyLocationRequestIfActive(reason: String) {
        if (
            !rt.state.isTracking ||
            rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        if (rt.locationRequests.shouldDebounceLocationRequestReapply(reason)) {
            val elapsedMs = System.currentTimeMillis() - rt.state.lastLocationRequestAppliedAtMs
            rt.deps.runtimeTelemetry.event(
                "location_request_reapply_suppressed",
                "reason=$reason elapsedMs=$elapsedMs"
            )
            rt.locationRequests.scheduleLocationRequestReapplyRetry(reason = "debounced_$reason")
            return
        }
        val applied = rt.locationRequests.applyCurrentLocationRequest(reason)
        if (!applied) {
            if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
                rt.foreground.failActiveTrackingAndStop(rt.locationRequests.resolveLocationRequestFailureMessage())
                return
            }
            rt.deps.runtimeTelemetry.event("location_request_reapply_deferred", "reason=$reason state=${rt.state.gpsRuntimeState}")
            rt.locationRequests.scheduleLocationRequestReapplyRetry(reason = reason)
        }
    }

    fun shouldDebounceLocationRequestReapply(reason: String): Boolean {
        return AutoMotionStabilityPolicy.shouldDebounceLocationRequestReapply(
            reason = reason,
            nowMs = System.currentTimeMillis(),
            lastAppliedAtMs = rt.state.lastLocationRequestAppliedAtMs,
            debounceMs = TrackingServiceConstants.AUTO_MOTION_REQUEST_REAPPLY_DEBOUNCE_MS,
        )
    }

    fun scheduleLocationRequestReapplyRetry(reason: String) {
        if (!rt.state.isTracking || rt.state.locationRequestReapplyRetryJob?.isActive == true) return
        val runGeneration = rt.state.trackingGeneration
        rt.state.locationRequestReapplyRetryJob = rt.serviceScope.launch {
            delay(TrackingServiceConstants.LOCATION_REQUEST_REAPPLY_RETRY_MS)
            if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) return@launch
            rt.state.locationRequestReapplyRetryJob = null
            rt.locationRequests.reapplyLocationRequestIfActive(reason = "retry_$reason")
        }
    }

    fun startFixDeliveryWatchdog() {
        if (rt.state.fixDeliveryWatchdogJob?.isActive == true) return
        val runGeneration = rt.state.trackingGeneration
        rt.state.fixDeliveryWatchdogJob = rt.serviceScope.launch {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                delay(TrackingServiceConstants.FIX_DELIVERY_WATCHDOG_INTERVAL_MS)
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) continue
                val nowMs = System.currentTimeMillis()
                val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(rt.deps.settingsRepository.getSettings())
                val localRecoveryDue = rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                )
                val decision = rt.deps.providerHealthController.evaluate(
                    nowMs = nowMs,
                    isTracking = rt.state.isTracking,
                    expectsActiveFixDelivery = rt.locationRequests.expectsActiveFixDelivery(),
                    gpsProviderAvailable = rt.utilities.isGpsProviderEnabled(),
                    localRecoveryDue = localRecoveryDue,
                )
                if (rt.deps.providerHealthController.shouldLog(decision)) {
                    val (eventName, details) = PositioningDiagnosticEvent.providerHealth(decision)
                    rt.deps.runtimeTelemetry.event(
                        eventName,
                        "$details gpsState=rt.state.gpsRuntimeState collectionPace=rt.state.collectionPace lastAppliedAt=${rt.state.lastLocationRequestAppliedAtMs}",
                    )
                }
                if (decision is ProviderHealthDecision.ReapplyRequest) {
                    if (decision.staleFreshness) {
                        rt.deps.freshnessRecoveryController.reset()
                        rt.deps.runtimeTelemetry.event(
                            "freshness_probe_reset",
                            "reason=callback_silent_provider_reapply localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L}"
                        )
                    }
                    rt.state.lastAppliedLocationRequestKey = null
                    rt.locationRequests.reapplyLocationRequestIfActive(reason = "fix_delivery_stale")
                }
            }
        }
    }

    fun expectsActiveFixDelivery(): Boolean {
        return LocationRequestController.expectsActiveFixDelivery(rt.state.isTracking, rt.state.gpsRuntimeState)
    }

    fun resolveLocationRequestFailureMessage(): String {
        return if (TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
            rt.ports.service.getString(R.string.unable_to_start_location_updates)
        } else {
            rt.ports.service.getString(R.string.location_permissions_required)
        }
    }

}

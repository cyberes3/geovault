package com.geovault.tracker.positioning.collection

import com.geovault.tracker.positioning.LocationRequestController
import com.geovault.tracker.positioning.PositioningRuntime
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingLocationRequestInput
import com.geovault.tracker.location.TrackingLocationRequestPolicy
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.LocationRequestKey
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.tracking.TrackingServiceConstants
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

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

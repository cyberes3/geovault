package com.geovault.tracker.tracking


import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.os.VibrationEffect
import android.os.VibratorManager
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
import android.provider.Settings
import com.geovault.common.logging.GeoVaultCaptureLog
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.TrackerLocationMotionContext
import com.geovault.tracker.location.TrackerLocationPipeline
import com.geovault.tracker.location.FixIngestMode
import com.geovault.tracker.location.TrackerLocationPipelineInput
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
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
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.PositioningDensity
import com.geovault.tracker.services.PositioningPresetValues
import com.geovault.tracker.services.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackerPositioningRuntimeContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.PositioningPolicyConfig
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.random.Random


    internal fun TrackingServiceHost.applyCurrentLocationRequest(reason: String): Boolean {
        if (!isTracking) return false
        if (
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return false
        }
        if (!TrackingPermissionGate.hasLocationPermission(service)) return false
        val runtimeContext = currentPositioningRuntimeContext()
        val intervalSec = runtimeContext.locationIntervalSec
        val distanceFilter = runtimeContext.distanceFilterMeters
        val requestKey = LocationRequestKey(
            intervalSec = intervalSec,
            distanceFilterMeters = distanceFilter,
            fastLock = isFastGpsLockWindowActive,
        )
        if (lastAppliedLocationRequestKey == requestKey) {
            runtimeTelemetry.decision(
                name = "location_request_unchanged",
                details = "reason=$reason intervalSec=$intervalSec distance=$distanceFilter fastLock=$isFastGpsLockWindowActive"
            )
            startFixDeliveryWatchdog()
            return true
        }
        val request = if (isFastGpsLockWindowActive) {
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
            val started = locationSessionCoordinator.startSession(request = request)
            if (!started) return false
            lastAppliedLocationRequestKey = requestKey
            lastLocationRequestAppliedAtMs = System.currentTimeMillis()
            providerHealthController.markRequestApplied(lastLocationRequestAppliedAtMs)
            locationRequestReapplyRetryJob?.cancel()
            locationRequestReapplyRetryJob = null
            runtimeTelemetry.decision(
                name = "location_request_applied",
                details = "reason=$reason intervalSec=$intervalSec distance=$distanceFilter fastLock=$isFastGpsLockWindowActive"
            )
            startFixDeliveryWatchdog()
            true
        } catch (security: SecurityException) {
            GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Location request failed reason=$reason", security)
            false
        }
    }

    internal fun TrackingServiceHost.reapplyLocationRequestIfActive(reason: String) {
        if (
            !isTracking ||
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        if (shouldDebounceLocationRequestReapply(reason)) {
            val elapsedMs = System.currentTimeMillis() - lastLocationRequestAppliedAtMs
            runtimeTelemetry.event(
                "location_request_reapply_suppressed",
                "reason=$reason elapsedMs=$elapsedMs"
            )
            scheduleLocationRequestReapplyRetry(reason = "debounced_$reason")
            return
        }
        val applied = applyCurrentLocationRequest(reason)
        if (!applied) {
            if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(service)) {
                failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
                return
            }
            runtimeTelemetry.event("location_request_reapply_deferred", "reason=$reason state=$gpsRuntimeState")
            scheduleLocationRequestReapplyRetry(reason = reason)
        }
    }

    internal fun TrackingServiceHost.shouldDebounceLocationRequestReapply(reason: String): Boolean {
        return AutoMotionStabilityPolicy.shouldDebounceLocationRequestReapply(
            reason = reason,
            nowMs = System.currentTimeMillis(),
            lastAppliedAtMs = lastLocationRequestAppliedAtMs,
            debounceMs = TrackingServiceConstants.AUTO_MOTION_REQUEST_REAPPLY_DEBOUNCE_MS,
        )
    }

    internal fun TrackingServiceHost.scheduleLocationRequestReapplyRetry(reason: String) {
        if (!isTracking || locationRequestReapplyRetryJob?.isActive == true) return
        val runGeneration = trackingGeneration
        locationRequestReapplyRetryJob = serviceScope.launch {
            delay(TrackingServiceConstants.LOCATION_REQUEST_REAPPLY_RETRY_MS)
            if (!isTracking || runGeneration != trackingGeneration) return@launch
            locationRequestReapplyRetryJob = null
            reapplyLocationRequestIfActive(reason = "retry_$reason")
        }
    }

    internal fun TrackingServiceHost.startFixDeliveryWatchdog() {
        if (fixDeliveryWatchdogJob?.isActive == true) return
        val runGeneration = trackingGeneration
        fixDeliveryWatchdogJob = serviceScope.launch {
            while (isTracking && runGeneration == trackingGeneration) {
                delay(TrackingServiceConstants.FIX_DELIVERY_WATCHDOG_INTERVAL_MS)
                if (!isTracking || runGeneration != trackingGeneration) continue
                val nowMs = System.currentTimeMillis()
                val runtimeContext = currentPositioningRuntimeContext(settingsRepository.getSettings())
                val localRecoveryDue = pointFreshnessTracker.shouldForceLocalRecovery(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                )
                val decision = providerHealthController.evaluate(
                    nowMs = nowMs,
                    isTracking = isTracking,
                    expectsActiveFixDelivery = expectsActiveFixDelivery(),
                    gpsProviderAvailable = isGpsProviderEnabled(),
                    localRecoveryDue = localRecoveryDue,
                )
                if (providerHealthController.shouldLog(decision)) {
                    val (eventName, details) = PositioningDiagnosticEvent.providerHealth(decision)
                    runtimeTelemetry.event(eventName, "$details gpsState=$gpsRuntimeState lastAppliedAt=$lastLocationRequestAppliedAtMs")
                }
                if (decision is ProviderHealthDecision.ReapplyRequest) {
                    if (decision.staleFreshness) {
                        freshnessRecoveryController.reset()
                        runtimeTelemetry.event(
                            "freshness_probe_reset",
                            "reason=callback_silent_provider_reapply localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L}"
                        )
                    }
                    lastAppliedLocationRequestKey = null
                    reapplyLocationRequestIfActive(reason = "fix_delivery_stale")
                }
            }
        }
    }

    internal fun TrackingServiceHost.expectsActiveFixDelivery(): Boolean {
        return LocationRequestController.expectsActiveFixDelivery(isTracking, gpsRuntimeState)
    }

    internal fun TrackingServiceHost.resolveLocationRequestFailureMessage(): String {
        return if (TrackingPermissionGate.hasRequiredPermissionsForTracking(service)) {
            service.getString(R.string.unable_to_start_location_updates)
        } else {
            service.getString(R.string.location_permissions_required)
        }
    }

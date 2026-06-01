package com.geovault.tracker.positioning
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.tracking.TrackingServiceConstants



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
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
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
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
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


    internal fun PositioningRuntime.failStartup(message: String, path: TrackingServiceIntents.StartupCommandPath, trigger: String, reason: String) {
        GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Tracking start failed: $reason path=$path trigger=$trigger")
        TrackPointBus.resumeLocalDelivery()
        transitionControlState(TrackingControlEvent.StartFailed, failureReason = message)
        transitionToStoppedState(failureReason = message)
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = "startup_failed")
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.STARTUP_FAILED,
            reason = reason,
            trigger = TrackingServiceIntents.mapRuntimeTrigger(trigger)
        )
        serviceScope.launch(Dispatchers.Main) {
            service.sendBroadcast(
                Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                    setPackage(service.packageName)
                    putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(service, message, Toast.LENGTH_LONG).show()
        }
        stopSelfSafelyAfterStartup(reason = "startup_failed")
    }

    internal fun PositioningRuntime.failActiveTrackingAndStop(message: String) {
        transitionControlState(TrackingControlEvent.FatalFailure, failureReason = message)
        serviceScope.launch(Dispatchers.Main) {
            service.sendBroadcast(
                Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                    setPackage(service.packageName)
                    putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(service, message, Toast.LENGTH_LONG).show()
        }
        stopTracking(reason = "fatal_failure", failureReason = message)
    }

    internal fun PositioningRuntime.promoteToForegroundForStartup(
        trigger: String,
        action: String?,
        path: TrackingServiceIntents.StartupCommandPath
    ): Boolean {
        if (startupForegroundPromoted) return true
        return try {
            service.startForeground(
                TrackingServiceConstants.NOTIFICATION_ID,
                notificationPresenter.buildTrackingNotification(runtimeSnapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            startupForegroundPromoted = true
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Foreground promotion succeeded trigger=$trigger")
            logNotificationSurfaceDiagnostics(
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
                    service.applicationContext,
                    reason = "fgs_start_failed_$trigger"
                )
            } else {
                runtimeTelemetry.decision(
                    "foreground_promotion_failed",
                    "trigger=$trigger path=$path action=${action ?: "none"} " +
                        "error=${e.javaClass.simpleName}:${e.message ?: "none"}"
                )
                TrackingRecoveryCoordinator.ensureWatchdogScheduled(service.applicationContext)
            }
            logNotificationSurfaceDiagnostics(
                trigger = trigger,
                action = action,
                path = path,
                stage = "foreground_promotion_failed"
            )
            false
        }
    }

    internal fun PositioningRuntime.stopSelfSafelyAfterStartup(reason: String) {
        cleanupServiceResources(reason = reason)
        stopServiceInstance(reason = reason)
    }

    internal fun PositioningRuntime.logNotificationSurfaceDiagnostics(
        trigger: String,
        action: String?,
        path: TrackingServiceIntents.StartupCommandPath,
        stage: String
    ) {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val userManager = service.getSystemService(Context.USER_SERVICE) as? UserManager
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

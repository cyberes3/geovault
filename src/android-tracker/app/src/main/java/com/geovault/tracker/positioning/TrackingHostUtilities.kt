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


    internal fun PositioningRuntime.isGpsProviderEnabled(): Boolean {
        return locationSessionCoordinator.isGpsProviderEnabled()
    }

    internal fun PositioningRuntime.readBatteryLevel(): Int {
        val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = batteryIntent.getIntExtra("level", -1)
        val scale = batteryIntent.getIntExtra("scale", -1)
        if (level <= 0 || scale <= 0) return 0
        return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    internal fun PositioningRuntime.isCharging(): Boolean {
        val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = batteryIntent.getIntExtra("status", -1)
        return status == 2 || status == 5
    }

    internal fun PositioningRuntime.publishTrackPoint(
        trackId: String,
        location: Location,
        propsJson: String?,
        quality: TrackPointQuality
    ) {
        val orderingKey = if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, false) == true) {
            location.time
        } else {
            localTrackPointOrderingCounter.incrementAndGet()
        }
        GeoVaultCaptureLog.d(
            TrackingServiceConstants.TAG,
            "map_update local_bus_publish track=${trackId.trim()} ts=${location.time} " +
                "order=$orderingKey lat=${location.latitude} lon=${location.longitude} " +
                "acc=${if (location.hasAccuracy()) location.accuracy else null} quality=$quality " +
                "manual=${location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, false) == true}"
        )
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lon = location.longitude,
                lat = location.latitude,
                timestampMs = location.time,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                propsJson = propsJson,
                quality = quality,
                orderingKey = orderingKey,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
                gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
            )
        )
    }

    internal fun PositioningRuntime.resolveTrackPointQuality(location: Location, propsJson: String?): TrackPointQuality {
        if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, false) == true) {
            return TrackPointQuality.DEGRADED
        }
        if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, false) == true) {
            return TrackPointQuality.DEGRADED
        }
        if (propsJson?.contains("\"fast_lock_timeout_best_sample\":true") == true) {
            return TrackPointQuality.DEGRADED
        }
        return TrackPointQuality.HIGH_CONFIDENCE
    }

    internal fun PositioningRuntime.triggerLightHaptic() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val vibratorManager = service.getSystemService(VibratorManager::class.java) ?: return
        val vibrator = vibratorManager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    internal fun PositioningRuntime.buildLocalPointPropsJson(location: Location, distanceMeters: Float): String? {
        val settings = settingsRepository.getSettings()
        if (!settings.sendExtendedData) return null
        return try {
            val props = JSONObject()
            val timestampMs = location.time
            val timestampSec = if (timestampMs >= 1_000_000_000_000L) timestampMs / 1000L else timestampMs
            props.put("timestamp", timestampSec)
            props.put("starttimestamp", runtimeSnapshot.sessionStartTimeMs)
            if (location.hasAccuracy()) props.put("acc", location.accuracy.toDouble())
            if (location.hasAltitude()) props.put("alt", location.altitude)
            if (location.hasBearing()) props.put("bearing", location.bearing.toDouble())
            if (location.hasSpeed()) props.put("spd_kph", location.speed * 3.6f)
            props.put("prov", location.provider ?: "geovault")
            props.put("dist", distanceMeters.toDouble())
            if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, false) == true) {
                props.put("low_accuracy_fallback", true)
                location.extras?.getString(TrackingServiceConstants.EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER)?.let { sourceProvider ->
                    props.put("fallback_source_provider", sourceProvider)
                }
            }
            if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, false) == true) {
                props.put("manual_send", true)
            }
            if (location.extras?.getBoolean(TrackingServiceIntents.EXTRAS_KEY_PAUSED_FRESHNESS, false) == true) {
                props.put(PausedFreshnessPointFactory.PROPS_KEY_PAUSED_FRESHNESS, true)
                location.extras?.getString(TrackingServiceIntents.EXTRAS_KEY_PAUSED_FRESHNESS_SOURCE_PROVIDER)?.let { sourceProvider ->
                    props.put(PausedFreshnessPointFactory.PROPS_KEY_SOURCE_PROVIDER, sourceProvider)
                }
            }
            location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }?.let { props.put("sat", it) }
            props.put("batt", readBatteryLevel())
            props.put("ischarging", isCharging())
            val deviceIdentifier = getDeviceIdentifier()
            if (deviceIdentifier.isNotEmpty()) {
                props.put("ser", deviceIdentifier)
            }
            props.toString()
        } catch (e: Exception) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Failed to build extended tracking point payload", e)
            null
        }
    }

    internal fun PositioningRuntime.getDeviceIdentifier(): String {
        val androidId = runCatching {
            Settings.Secure.getString(service.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank()) {
            return androidId
        }
        return service.packageName
    }

internal fun PositioningRuntime.isWaitingForProviderState(): Boolean =
    GpsProviderWaitPolicy.isWaitingForProviderState(gpsRuntimeState)

internal fun PositioningRuntime.resolveObservedSpeedMps(
    location: Location,
    referenceLocation: Location?,
): Float? = ObservedSpeedResolver.resolveObservedSpeedMps(location, referenceLocation)

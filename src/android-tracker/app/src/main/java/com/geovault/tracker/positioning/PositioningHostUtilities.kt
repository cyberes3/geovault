package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.provider.Settings
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import org.json.JSONObject

internal class PositioningHostUtilities(private val rt: PositioningRuntime) {
    fun isGpsProviderEnabled(): Boolean {
        return rt.deps.locationSessionCoordinator.isGpsProviderEnabled()
    }

    fun readBatteryLevel(): Int {
        val batteryIntent = rt.ports.service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = batteryIntent.getIntExtra("level", -1)
        val scale = batteryIntent.getIntExtra("scale", -1)
        if (level <= 0 || scale <= 0) return 0
        return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    fun isCharging(): Boolean {
        val batteryIntent = rt.ports.service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = batteryIntent.getIntExtra("status", -1)
        return status == 2 || status == 5
    }

    fun publishTrackPoint(
        trackId: String,
        location: Location,
        propsJson: String?,
        quality: TrackPointQuality
    ) {
        val orderingKey = if (location.extras?.getBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, false) == true) {
            location.time
        } else {
            rt.localTrackPointOrderingCounter.incrementAndGet()
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

    fun resolveTrackPointQuality(location: Location, propsJson: String?): TrackPointQuality {
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

    fun triggerLightHaptic() {
        rt.ports.triggerLightHaptic()
    }

    fun buildLocalPointPropsJson(location: Location, distanceMeters: Float): String? {
        val settings = rt.deps.settingsRepository.getSettings()
        if (!settings.sendExtendedData) return null
        return try {
            val props = JSONObject()
            val timestampMs = location.time
            val timestampSec = if (timestampMs >= 1_000_000_000_000L) timestampMs / 1000L else timestampMs
            props.put("timestamp", timestampSec)
            props.put("starttimestamp", rt.state.runtimeSnapshot.sessionStartTimeMs)
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
            props.put("batt", rt.utilities.readBatteryLevel())
            props.put("ischarging", rt.utilities.isCharging())
            val deviceIdentifier = rt.utilities.getDeviceIdentifier()
            if (deviceIdentifier.isNotEmpty()) {
                props.put("ser", deviceIdentifier)
            }
            props.toString()
        } catch (e: Exception) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Failed to build extended tracking point payload", e)
            null
        }
    }

    fun getDeviceIdentifier(): String {
        val androidId = runCatching {
            Settings.Secure.getString(rt.ports.service.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank()) {
            return androidId
        }
        return rt.ports.service.packageName
    }

    fun isWaitingForProviderState(): Boolean =
        GpsProviderWaitPolicy.isWaitingForProviderState(rt.state.gpsRuntimeState)

    fun resolveObservedSpeedMps(
        location: Location,
        referenceLocation: Location?,
    ): Float? = ObservedSpeedResolver.resolveObservedSpeedMps(location, referenceLocation)
}

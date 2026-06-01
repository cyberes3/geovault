package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

data class TrackingStatusAccuracyInput(
    val isRunning: Boolean,
    val gpsProviderEnabled: Boolean,
    val gpsState: GpsRuntimeState,
    val lastAccuracyMeters: Float?,
    val currentFixAccuracyMeters: Float?,
    val effectiveAccuracyThresholdMeters: Float,
    val activeAccuracyBlockedEmission: Boolean,
)

data class TrackingStatusAccuracyProjection(
    val uiStatus: TrackingUiStatus,
    val statusAccuracyMeters: Float?,
    val displayAccuracyMeters: Float?,
)

object TrackingStatusAccuracyProjector {
    @JvmStatic
    fun project(input: TrackingStatusAccuracyInput): TrackingStatusAccuracyProjection {
        val statusAccuracy = input.currentFixAccuracyMeters ?: input.lastAccuracyMeters
        val uiStatus = TrackingUiStatusResolver.resolveForGpsState(
            isRunning = input.isRunning,
            gpsProviderEnabled = input.gpsProviderEnabled,
            gpsState = input.gpsState,
            lastAccuracyMeters = statusAccuracy,
            effectiveAccuracyThresholdMeters = input.effectiveAccuracyThresholdMeters,
            activeAccuracyBlockedEmission = input.activeAccuracyBlockedEmission,
        )
        return TrackingStatusAccuracyProjection(
            uiStatus = uiStatus,
            statusAccuracyMeters = statusAccuracy,
            displayAccuracyMeters = displayAccuracy(
                uiStatus = uiStatus,
                lastAccuracyMeters = input.lastAccuracyMeters,
                currentFixAccuracyMeters = input.currentFixAccuracyMeters,
            ),
        )
    }

    @JvmStatic
    fun displayAccuracy(
        uiStatus: TrackingUiStatus,
        lastAccuracyMeters: Float?,
        currentFixAccuracyMeters: Float?,
    ): Float? {
        return if (uiStatus == TrackingUiStatus.LOCKING && currentFixAccuracyMeters != null) {
            currentFixAccuracyMeters
        } else {
            lastAccuracyMeters
        }
    }
}

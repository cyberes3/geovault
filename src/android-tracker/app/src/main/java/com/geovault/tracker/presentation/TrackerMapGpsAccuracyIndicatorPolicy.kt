package com.geovault.tracker.presentation

import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerMapGpsAccuracyIndicatorUiModel(
    val isVisible: Boolean = false,
)

object TrackerMapGpsAccuracyIndicatorPolicy {
    fun resolve(runtime: TrackingRuntimeSnapshot): TrackerMapGpsAccuracyIndicatorUiModel {
        if (!runtime.gpsCollecting) {
            return TrackerMapGpsAccuracyIndicatorUiModel(isVisible = false)
        }
        val noGoodFix = runtime.lastAccuracyMeters == null ||
            runtime.lastAccuracyMeters > runtime.effectiveAccuracyThresholdMeters
        return TrackerMapGpsAccuracyIndicatorUiModel(isVisible = noGoodFix)
    }
}

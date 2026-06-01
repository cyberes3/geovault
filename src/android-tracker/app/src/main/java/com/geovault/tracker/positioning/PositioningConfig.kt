package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings

internal object PositioningConfig {
    fun resolveContext(
        state: PositioningSessionState,
        settings: TrackerSettings,
        activeMotionMode: TrackingMotionMode,
        pointFreshnessTracker: PointFreshnessTracker,
    ): PositioningContext {
        val density = PositioningDensity.from(settings)
        val preset = PositioningPresets.forMotionMode(activeMotionMode, density)
        val effectiveDistance = state.elasticDistanceOverrideMeters ?: preset.distanceFilterMeters
        return PositioningContext.build(
            settings = settings,
            activeMotionMode = activeMotionMode,
            effectiveDistanceFilterMeters = effectiveDistance,
            localPointMaxGapMs = pointFreshnessTracker.maxAllowedPointGapMs(preset.locationIntervalSec),
            collectionPace = state.collectionPace,
        )
    }
}

package com.geovault.tracker.fragments.map

import org.maplibre.android.maps.MapLibreMap

internal object MapCameraInteractionPolicy {
    fun isManualCameraInteraction(cameraMoveStartedReason: Int): Boolean {
        return cameraMoveStartedReason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
    }
}

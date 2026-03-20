package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap

class MapCameraInteractionPolicyTest {

    @Test
    fun isManualCameraInteraction_trueForGestureReason() {
        val isManual = MapCameraInteractionPolicy.isManualCameraInteraction(
            cameraMoveStartedReason = MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
        )

        assertTrue(isManual)
    }

    @Test
    fun isManualCameraInteraction_falseForApiAnimationReason() {
        val isManual = MapCameraInteractionPolicy.isManualCameraInteraction(
            cameraMoveStartedReason = MapLibreMap.OnCameraMoveStartedListener.REASON_API_ANIMATION
        )

        assertFalse(isManual)
    }

    @Test
    fun isManualCameraInteraction_falseForDeveloperAnimationReason() {
        val isManual = MapCameraInteractionPolicy.isManualCameraInteraction(
            cameraMoveStartedReason = MapLibreMap.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION
        )

        assertFalse(isManual)
    }

    @Test
    fun isManualCameraInteraction_falseForUnknownReason() {
        val isManual = MapCameraInteractionPolicy.isManualCameraInteraction(
            cameraMoveStartedReason = -1
        )

        assertFalse(isManual)
    }
}

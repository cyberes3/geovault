package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap

class GeoVaultCameraHooksTest {

    @Test
    fun createGestureMoveStartedListener_triggersOnlyForGestureReason() {
        var gestureCalls = 0
        val listener = geoVaultCreateGestureMoveStartedListener {
            gestureCalls++
        }

        listener.onCameraMoveStarted(MapLibreMap.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION)
        listener.onCameraMoveStarted(MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)

        assertEquals(1, gestureCalls)
    }
}

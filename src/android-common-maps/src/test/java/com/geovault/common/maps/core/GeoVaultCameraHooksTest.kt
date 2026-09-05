package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

class GeoVaultCameraHooksTest {

    @Test
    fun retargetCameraPositionPreserveViewport_changesOnlyTarget() {
        val originalTarget = LatLng(45.0, -120.0)
        val nextTarget = LatLng(46.0, -121.0)
        val current = CameraPosition.Builder()
            .target(originalTarget)
            .zoom(16.5)
            .bearing(32.0)
            .tilt(45.0)
            .build()

        val retargeted = geoVaultRetargetCameraPositionPreserveViewport(
            current = current,
            target = nextTarget,
        )

        assertEquals(nextTarget, retargeted.target)
        assertEquals(current.zoom, retargeted.zoom, 0.0)
        assertEquals(current.bearing, retargeted.bearing, 0.0)
        assertEquals(current.tilt, retargeted.tilt, 0.0)
    }

    @Test
    fun retargetCameraPositionWithMinimumZoom_zoomsInWhenCurrentZoomIsTooBroad() {
        val current = CameraPosition.Builder()
            .target(LatLng(45.0, -120.0))
            .zoom(7.0)
            .bearing(32.0)
            .tilt(45.0)
            .build()
        val nextTarget = LatLng(46.0, -121.0)

        val retargeted = geoVaultRetargetCameraPositionWithMinimumZoom(
            current = current,
            target = nextTarget,
            minimumZoom = 12.0,
        )

        assertEquals(nextTarget, retargeted.target)
        assertEquals(12.0, retargeted.zoom, 0.0)
        assertEquals(current.bearing, retargeted.bearing, 0.0)
        assertEquals(current.tilt, retargeted.tilt, 0.0)
    }

    @Test
    fun retargetCameraPositionWithMinimumZoom_doesNotZoomOutWhenCurrentZoomIsCloser() {
        val current = CameraPosition.Builder()
            .target(LatLng(45.0, -120.0))
            .zoom(16.5)
            .bearing(32.0)
            .tilt(45.0)
            .build()
        val nextTarget = LatLng(46.0, -121.0)

        val retargeted = geoVaultRetargetCameraPositionWithMinimumZoom(
            current = current,
            target = nextTarget,
            minimumZoom = 12.0,
        )

        assertEquals(nextTarget, retargeted.target)
        assertEquals(current.zoom, retargeted.zoom, 0.0)
        assertEquals(current.bearing, retargeted.bearing, 0.0)
        assertEquals(current.tilt, retargeted.tilt, 0.0)
    }
}

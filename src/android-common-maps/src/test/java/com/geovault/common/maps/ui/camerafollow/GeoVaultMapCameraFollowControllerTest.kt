package com.geovault.common.maps.ui.camerafollow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

class GeoVaultMapCameraFollowControllerTest {
    @Test
    fun positionOnlyLocationFixMovesCameraWhenFollowIsActive() {
        val camera = FakeFollowCamera()
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
            allowFollowCamera = true,
        )

        controller.onLocationFix(LatLng(45.0, -120.0))

        assertEquals(LatLng(45.0, -120.0), camera.current.target)
        assertEquals(12.0, camera.current.zoom, 0.0)
        assertEquals(1, camera.moveCount)
        assertEquals(1, camera.ensureInteractiveGestureCount)
    }

    @Test
    fun recenterUsesMinimumZoomAndNeverZoomsOut() {
        val camera = FakeFollowCamera(
            CameraPosition.Builder()
                .target(LatLng(0.0, 0.0))
                .zoom(12.5)
                .bearing(33.0)
                .tilt(12.0)
                .build(),
        )
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
            allowFollowCamera = true,
        )

        controller.recenter(LatLng(45.0, -120.0))

        assertEquals(LatLng(45.0, -120.0), camera.current.target)
        assertEquals(12.5, camera.current.zoom, 0.0)
        assertEquals(33.0, camera.current.bearing, 0.0)
        assertEquals(12.0, camera.current.tilt, 0.0)
        assertEquals(1, camera.animateCount)
    }

    @Test
    fun locationFixDuringRecenterMovesAfterAnimationFinishes() {
        val camera = FakeFollowCamera(
            CameraPosition.Builder()
                .target(LatLng(0.0, 0.0))
                .zoom(6.0)
                .build(),
        )
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
            allowFollowCamera = true,
        )

        controller.recenter(LatLng(45.0, -120.0))
        controller.onLocationFix(LatLng(45.1, -120.1))

        assertEquals(LatLng(45.0, -120.0), camera.current.target)
        assertEquals(10.0, camera.current.zoom, 0.0)
        assertEquals(0, camera.moveCount)

        camera.finishLatestAnimation()

        assertEquals(LatLng(45.1, -120.1), camera.current.target)
        assertEquals(10.0, camera.current.zoom, 0.0)
        assertEquals(1, camera.moveCount)
    }

    @Test
    fun staleRecenterCallbackCannotStopLaterFollowHandoff() {
        val camera = FakeFollowCamera()
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
            allowFollowCamera = true,
        )

        controller.recenter(LatLng(45.0, -120.0))
        val staleCallback = camera.latestCallback
        controller.recenter(LatLng(46.0, -121.0))
        controller.onLocationFix(LatLng(46.1, -121.1))

        staleCallback?.onFinish()

        assertEquals(LatLng(46.0, -121.0), camera.current.target)
        assertEquals(0, camera.moveCount)

        camera.finishLatestAnimation()

        assertEquals(LatLng(46.1, -121.1), camera.current.target)
        assertEquals(1, camera.moveCount)
    }

    @Test
    fun headingAndPositionUsesBearingToMoveCamera() {
        val camera = FakeFollowCamera()
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = true,
            allowFollowCamera = true,
        )

        controller.onLocationFix(LatLng(45.0, -120.0))
        assertEquals(0, camera.moveCount)

        controller.onBearing(87f)

        assertEquals(LatLng(45.0, -120.0), camera.current.target)
        assertEquals(87.0, camera.current.bearing, 0.0)
        assertEquals(1, camera.moveCount)
    }

    @Test
    fun disallowedFollowDoesNotMoveCamera() {
        val camera = FakeFollowCamera()
        val controller = GeoVaultMapCameraFollowController(camera, minimumRecenterZoom = 10.0)
        controller.updateFollowState(
            positionFollowDesired = true,
            headingFollowDesired = false,
            allowFollowCamera = false,
        )

        controller.recenter(LatLng(45.0, -120.0))
        controller.onLocationFix(LatLng(45.1, -120.1))

        assertEquals(LatLng(0.0, 0.0), camera.current.target)
        assertEquals(0, camera.moveCount)
        assertEquals(0, camera.animateCount)
        assertNull(camera.latestCallback)
    }

    private class FakeFollowCamera(
        initial: CameraPosition = CameraPosition.Builder()
            .target(LatLng(0.0, 0.0))
            .zoom(12.0)
            .bearing(0.0)
            .tilt(0.0)
            .build(),
    ) : GeoVaultMapCameraFollowController.Camera {
        var current: CameraPosition = initial
            private set
        var latestCallback: MapLibreMap.CancelableCallback? = null
            private set
        var moveCount: Int = 0
            private set
        var animateCount: Int = 0
            private set
        var ensureInteractiveGestureCount: Int = 0
            private set

        override fun currentPosition(): CameraPosition = current

        override fun moveTo(position: CameraPosition) {
            moveCount += 1
            current = position
        }

        override fun animateTo(
            position: CameraPosition,
            callback: MapLibreMap.CancelableCallback,
        ) {
            animateCount += 1
            current = position
            latestCallback = callback
        }

        override fun ensureInteractiveGestures() {
            ensureInteractiveGestureCount += 1
        }

        fun finishLatestAnimation() {
            latestCallback?.onFinish()
        }
    }
}

package com.geovault.common.maps.ui.camerafollow

import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.geoVaultRetargetCameraPositionPreserveViewport
import com.geovault.common.maps.core.geoVaultRetargetCameraPositionWithMinimumZoom
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

internal class GeoVaultMapCameraFollowController(
    private val camera: Camera,
    private val minimumRecenterZoom: Double,
) {
    interface Camera {
        fun currentPosition(): CameraPosition?
        fun moveTo(position: CameraPosition)
        fun animateTo(
            position: CameraPosition,
            callback: MapLibreMap.CancelableCallback,
        )
        fun ensureInteractiveGestures()
    }

    private data class FollowState(
        val position: Boolean,
        val heading: Boolean,
        val allowed: Boolean,
    ) {
        val active: Boolean = allowed && (position || heading)
        val positionOnly: Boolean = allowed && position && !heading
        val positionAndHeading: Boolean = allowed && position && heading
    }

    private data class Recentering(val requestId: Int)

    private var followState = FollowState(
        position = false,
        heading = false,
        allowed = false,
    )
    private var latestLocation: LatLng? = null
    private var nextRecenterRequestId: Int = 0
    private var recentering: Recentering? = null

    fun updateFollowState(
        positionFollowDesired: Boolean,
        headingFollowDesired: Boolean,
        allowFollowCamera: Boolean,
    ) {
        followState = FollowState(
            position = positionFollowDesired,
            heading = headingFollowDesired,
            allowed = allowFollowCamera,
        )
        if (!followState.position) {
            recentering = null
        }
        if (followState.active) {
            camera.ensureInteractiveGestures()
        }
    }

    fun clearFollow() {
        followState = FollowState(
            position = false,
            heading = false,
            allowed = false,
        )
        recentering = null
    }

    fun recenter(target: LatLng) {
        if (!followState.allowed || !followState.position) return
        latestLocation = target
        val current = camera.currentPosition() ?: return
        val next = geoVaultRetargetCameraPositionWithMinimumZoom(
            current = current,
            target = target,
            minimumZoom = minimumRecenterZoom,
        )
        if (followState.heading) {
            camera.moveTo(next)
            applyLatestLocationAfterRecenter()
            return
        }
        val request = Recentering(++nextRecenterRequestId)
        recentering = request
        camera.animateTo(
            position = next,
            callback = object : MapLibreMap.CancelableCallback {
                override fun onCancel() {
                    finishRecenter(request)
                }

                override fun onFinish() {
                    finishRecenter(request)
                }
            },
        )
    }

    fun onLocationFix(target: LatLng) {
        latestLocation = target
        if (recentering != null) return
        if (!followState.positionOnly) return
        val current = camera.currentPosition() ?: return
        camera.moveTo(
            geoVaultRetargetCameraPositionPreserveViewport(
                current = current,
                target = target,
            ),
        )
    }

    fun onBearing(bearingDegrees: Float) {
        if (!followState.positionAndHeading) return
        val target = latestLocation ?: return
        val current = camera.currentPosition() ?: return
        camera.moveTo(
            CameraPosition.Builder(current)
                .target(target)
                .bearing(bearingDegrees.toDouble())
                .build(),
        )
    }

    private fun finishRecenter(request: Recentering) {
        if (recentering != request) return
        recentering = null
        applyLatestLocationAfterRecenter()
    }

    private fun applyLatestLocationAfterRecenter() {
        val target = latestLocation ?: return
        when {
            followState.positionOnly -> {
                val current = camera.currentPosition() ?: return
                camera.moveTo(
                    geoVaultRetargetCameraPositionPreserveViewport(
                        current = current,
                        target = target,
                    ),
                )
            }
            followState.positionAndHeading -> Unit
        }
    }
}

internal class GeoVaultBaseMapFollowCamera(
    private val map: GeoVaultBaseMap,
) : GeoVaultMapCameraFollowController.Camera {
    override fun currentPosition(): CameraPosition? =
        map.maplibreMap?.cameraPosition

    override fun moveTo(position: CameraPosition) {
        val libre = map.maplibreMap ?: return
        libre.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    override fun animateTo(
        position: CameraPosition,
        callback: MapLibreMap.CancelableCallback,
    ) {
        val libre = map.maplibreMap ?: return
        libre.animateCamera(
            CameraUpdateFactory.newCameraPosition(position),
            300,
            callback,
        )
    }

    override fun ensureInteractiveGestures() {
        map.ensureInteractiveGestures()
    }
}

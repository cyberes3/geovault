package com.geovault.common.maps.ui.camerafollow

import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.geoVaultRetargetCameraPositionPreserveViewport
import com.geovault.common.maps.core.geoVaultRetargetCameraPositionWithMinimumZoom
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

internal class GeoVaultMapCameraFollowController(
    private val camera: Camera,
    private val minimumRecenterZoom: Double,
) {
    interface Camera {
        fun currentPosition(): CameraPosition?
        fun moveTo(position: CameraPosition)
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

    private var followState = FollowState(
        position = false,
        heading = false,
        allowed = false,
    )
    private var latestLocation: LatLng? = null

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
            return
        }
        // Position-only follow: snap immediately on FAB recenter (no ease animation).
        camera.moveTo(next)
    }

    fun onLocationFix(target: LatLng) {
        val previousLocation = latestLocation
        latestLocation = target
        if (!followState.positionOnly) return
        val current = camera.currentPosition() ?: return
        if (previousLocation == target && current.target == target) return
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

    override fun ensureInteractiveGestures() {
        map.ensureInteractiveGestures()
    }
}

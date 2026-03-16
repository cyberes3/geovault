package com.geovault.tracker.fragments.map

import com.geovault.common.map.MapLibreManager
import com.geovault.tracker.R
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

internal object MapCameraController {
    fun applyUnifiedCameraMove(
        mapManager: MapLibreManager,
        map: MapLibreMap,
        update: CameraUpdate,
        paddingMode: CameraPaddingMode,
        followLockPadding: DoubleArray,
        overlayAwarePadding: DoubleArray,
        animate: Boolean,
        durationMs: Int,
        callback: MapLibreMap.CancelableCallback?
    ) {
        val padding = when (paddingMode) {
            CameraPaddingMode.CENTERED -> followLockPadding
            CameraPaddingMode.OVERLAY_AWARE -> overlayAwarePadding
        }
        if (animate) {
            mapManager.animateCameraWithPadding(map, update, padding, durationMs, callback)
        } else {
            mapManager.moveCameraWithPadding(map, update, padding)
        }
    }

    fun buildFollowLockCameraUpdate(
        map: MapLibreMap,
        target: LatLng,
        followLockNeedsInitialZoom: Boolean,
        forceZoomIn: Boolean,
        followLockTargetZoom: Double
    ): Pair<CameraUpdate, Boolean> {
        val shouldForceZoom = forceZoomIn || followLockNeedsInitialZoom
        val zoom = if (shouldForceZoom) {
            maxOf(map.cameraPosition.zoom, followLockTargetZoom)
        } else {
            map.cameraPosition.zoom
        }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target).zoom(zoom).build()
        )
        return update to shouldForceZoom
    }

    fun zoomButtonsPaddingMode(activeCameraIntent: CameraIntent, isFollowLockActive: Boolean): CameraPaddingMode {
        val centeredIntent = activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS ||
            activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
            activeCameraIntent == CameraIntent.FOLLOW_LOCK
        return if (isFollowLockActive || centeredIntent) {
            CameraPaddingMode.CENTERED
        } else {
            CameraPaddingMode.OVERLAY_AWARE
        }
    }

    fun followLockButtonContent(isLockActive: Boolean): Pair<Int, Int> {
        return if (isLockActive) {
            R.drawable.ic_crosshair_locked to R.string.follow_lock_on_description
        } else {
            R.drawable.ic_crosshair to R.string.zoom_to_latest_description
        }
    }
}

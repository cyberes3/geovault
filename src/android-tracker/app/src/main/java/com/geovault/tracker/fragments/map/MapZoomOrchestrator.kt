package com.geovault.tracker.fragments.map

import android.location.Location
import com.geovault.common.map.MapLibreManager
import com.geovault.tracker.Tracker
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.max
import kotlin.math.min

internal object MapZoomOrchestrator {
    fun applyUnifiedCameraMove(
        mapManager: MapLibreManager?,
        map: MapLibreMap,
        update: CameraUpdate,
        paddingMode: CameraPaddingMode,
        followLockPadding: DoubleArray,
        overlayAwarePadding: DoubleArray,
        intent: CameraIntent? = null,
        onIntent: (CameraIntent) -> Unit,
        animate: Boolean = false,
        durationMs: Int = MapConstants.FOLLOW_LOCK_ANIMATION_MS,
        callback: MapLibreMap.CancelableCallback? = null
    ) {
        val manager = mapManager ?: return
        intent?.let(onIntent)
        MapCameraController.applyUnifiedCameraMove(
            mapManager = manager,
            map = map,
            update = update,
            paddingMode = paddingMode,
            followLockPadding = followLockPadding,
            overlayAwarePadding = overlayAwarePadding,
            animate = animate,
            durationMs = durationMs,
            callback = callback
        )
    }

    fun zoomButtonsPaddingMode(activeCameraIntent: CameraIntent, isFollowLockActive: Boolean): CameraPaddingMode {
        return MapCameraController.zoomButtonsPaddingMode(activeCameraIntent, isFollowLockActive)
    }

    fun zoomToStandaloneLocation(
        map: MapLibreMap?,
        location: Location,
        forceZoomIn: Boolean = true,
        animate: Boolean = true,
        followLockTargetZoom: Double,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ): Boolean {
        val currentMap = map ?: return false
        val targetZoom = if (forceZoomIn) {
            // Treat explicit recenter taps as "zoom in now", not just "enforce minimum zoom once".
            val steppedZoom = currentMap.cameraPosition.zoom + 1.0
            min(currentMap.maxZoomLevel, max(steppedZoom, followLockTargetZoom))
        } else {
            currentMap.cameraPosition.zoom
        }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(targetZoom)
                .build()
        )
        applyMove(
            update,
            CameraPaddingMode.CENTERED,
            null,
            animate,
            MapConstants.FOLLOW_LOCK_ANIMATION_MS,
            null
        )
        return true
    }

    fun centerCameraOnTrackLocked(
        map: MapLibreMap?,
        target: LatLng,
        forceZoomIn: Boolean = false,
        followLockNeedsInitialZoom: Boolean,
        followLockTargetZoom: Double,
        followLockTargetZoomEpsilon: Double,
        isAdded: () -> Boolean,
        onFollowLockNeedsInitialZoomChanged: (Boolean) -> Unit,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ) {
        val currentMap = map ?: return
        val (update, shouldForceZoom) = MapCameraController.buildFollowLockCameraUpdate(
            map = currentMap,
            target = target,
            followLockNeedsInitialZoom = followLockNeedsInitialZoom,
            forceZoomIn = forceZoomIn,
            followLockTargetZoom = followLockTargetZoom
        )
        val callback = if (shouldForceZoom) {
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    if (!isAdded()) return
                    val reachedTarget = currentMap.cameraPosition.zoom >=
                        (followLockTargetZoom - followLockTargetZoomEpsilon)
                    if (reachedTarget) onFollowLockNeedsInitialZoomChanged(false)
                }

                override fun onCancel() {
                    // Keep initial-zoom request armed so the next lock update can complete it.
                }
            }
        } else {
            null
        }
        applyMove(
            update,
            CameraPaddingMode.CENTERED,
            CameraIntent.FOLLOW_LOCK,
            true,
            MapConstants.FOLLOW_LOCK_ANIMATION_MS,
            callback
        )
    }

    fun zoomToLatestTrackPoint(
        trackPoints: List<LatLng>,
        fallbackLastPoint: List<Double>?,
        followLockTargetZoom: Double,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ): Boolean {
        val target = MapDataLoader.resolveSingleTrackerZoomTarget(trackPoints, fallbackLastPoint) ?: return false
        applyMove(
            CameraUpdateFactory.newLatLngZoom(target, followLockTargetZoom),
            CameraPaddingMode.CENTERED,
            CameraIntent.SINGLE_TRACKER_FOCUS,
            false,
            MapConstants.FOLLOW_LOCK_ANIMATION_MS,
            null
        )
        return true
    }

    fun moveCameraToFitBoundsWithMinZoomClamp(
        map: MapLibreMap,
        bounds: LatLngBounds,
        minZoom: Double,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double,
        intent: CameraIntent? = null,
        getBoundsPaddingEdgesPx: (Int) -> IntArray,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ) {
        MapBoundsFitController.moveCameraToFitBoundsWithMinZoomClamp(
            map = map,
            bounds = bounds,
            minZoom = minZoom,
            intent = intent,
            minViewportWidthFraction = minViewportWidthFraction,
            minViewportHeightFraction = minViewportHeightFraction,
            getBoundsPaddingEdgesPx = getBoundsPaddingEdgesPx
        ) { update, paddingMode, moveIntent ->
            applyMove(
                update,
                paddingMode,
                moveIntent,
                false,
                MapConstants.FOLLOW_LOCK_ANIMATION_MS,
                null
            )
        }
    }

    fun moveCameraToFitBoundsCenteredWithMinZoomClamp(
        map: MapLibreMap,
        bounds: LatLngBounds,
        minZoom: Double,
        intent: CameraIntent? = null,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ) {
        MapBoundsFitController.moveCameraToFitBoundsCenteredWithMinZoomClamp(
            map = map,
            bounds = bounds,
            minZoom = minZoom,
            intent = intent
        ) { update, paddingMode, moveIntent ->
            applyMove(
                update,
                paddingMode,
                moveIntent,
                false,
                MapConstants.FOLLOW_LOCK_ANIMATION_MS,
                null
            )
        }
    }

    fun moveCameraForAllTrackersWithMinZoom(
        map: MapLibreMap,
        bounds: LatLngBounds,
        coordsByTrackerId: Map<String, List<LatLng>>,
        trackers: List<Tracker>,
        fitToTrackerId: String?,
        minZoom: Double,
        trackerCardFocusZoom: Double,
        preserveCenteredAllTrackersFit: Boolean,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double,
        tag: String,
        getBoundsPaddingEdgesPx: (Int) -> IntArray,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ): Boolean {
        return MapBoundsFitController.moveCameraForAllTrackersWithMinZoom(
            map = map,
            bounds = bounds,
            coordsByTrackerId = coordsByTrackerId,
            trackers = trackers,
            fitToTrackerId = fitToTrackerId,
            minZoom = minZoom,
            trackerCardFocusZoom = trackerCardFocusZoom,
            preserveCenteredAllTrackersFit = preserveCenteredAllTrackersFit,
            tag = tag,
            minViewportWidthFraction = minViewportWidthFraction,
            minViewportHeightFraction = minViewportHeightFraction,
            getBoundsPaddingEdgesPx = getBoundsPaddingEdgesPx
        ) { update, paddingMode, moveIntent ->
            applyMove(
                update,
                paddingMode,
                moveIntent,
                false,
                MapConstants.FOLLOW_LOCK_ANIMATION_MS,
                null
            )
        }
    }

    fun refreshMapPadding(
        map: MapLibreMap?,
        mapManager: MapLibreManager?,
        targetPadding: DoubleArray,
        force: Boolean = false,
        applyToCamera: Boolean = true,
        applyMove: (
            update: CameraUpdate,
            paddingMode: CameraPaddingMode,
            intent: CameraIntent?,
            animate: Boolean,
            durationMs: Int,
            callback: MapLibreMap.CancelableCallback?
        ) -> Unit
    ) {
        val currentMap = map ?: return
        val manager = mapManager ?: return
        val isSamePadding = MapPaddingRefresher.isSamePadding(currentMap.cameraPosition, targetPadding)
        manager.defaultPadding = targetPadding
        if (!applyToCamera) return
        if (isSamePadding) {
            if (!force) return
            applyMove(
                CameraUpdateFactory.newCameraPosition(currentMap.cameraPosition),
                CameraPaddingMode.OVERLAY_AWARE,
                null,
                false,
                MapConstants.FOLLOW_LOCK_ANIMATION_MS,
                null
            )
            return
        }
        val padded = CameraPosition.Builder(currentMap.cameraPosition)
            .padding(targetPadding)
            .build()
        applyMove(
            CameraUpdateFactory.newCameraPosition(padded),
            CameraPaddingMode.OVERLAY_AWARE,
            null,
            false,
            MapConstants.FOLLOW_LOCK_ANIMATION_MS,
            null
        )
    }

    fun shouldApplyPaddingForCurrentMode(
        allowCameraMove: Boolean,
        isFollowLockActive: Boolean,
        activeCameraIntent: CameraIntent,
        liveActiveFitEnabled: Boolean,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext,
        preserveCenteredAllTrackersFit: Boolean
    ): Boolean {
        // Keep centered focus targets (single/group member) from drifting when UI padding refreshes.
        val preserveCenteredGroupFocus = activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS ||
            activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS
        val preserveSingleLiveFit = liveActiveFitEnabled &&
            !showAllTrackers &&
            mapViewContext == MapViewContext.SINGLE_TRACKER
        val preserveCenteredAllTrackers = showAllTrackers &&
            activeCameraIntent == CameraIntent.BOUNDS_FIT &&
            preserveCenteredAllTrackersFit
        return MapPaddingRefresher.shouldApplyCameraPadding(
            allowCameraMove = allowCameraMove,
            isFollowLockActive = isFollowLockActive,
            preserveCenteredGroupFocus = preserveCenteredGroupFocus,
            preserveSingleLiveFit = preserveSingleLiveFit,
            preserveCenteredAllTrackersFit = preserveCenteredAllTrackers
        )
    }

    fun boundsPaddingEdgesFromInsets(insets: DoubleArray, extraBoundsPaddingPx: Int): IntArray {
        return intArrayOf(
            insets[0].toInt() + extraBoundsPaddingPx,
            insets[1].toInt() + extraBoundsPaddingPx,
            insets[2].toInt() + extraBoundsPaddingPx,
            insets[3].toInt() + extraBoundsPaddingPx
        )
    }

    fun sanitizeBoundsFitPaddingPx(
        map: MapLibreMap,
        rawPaddingPx: IntArray,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double
    ): IntArray {
        return MapCameraMath.sanitizeBoundsFitPaddingPx(
            mapWidthPxRaw = map.width.toInt(),
            mapHeightPxRaw = map.height.toInt(),
            rawPaddingPx = rawPaddingPx,
            minViewportWidthFraction = minViewportWidthFraction,
            minViewportHeightFraction = minViewportHeightFraction
        )
    }
}

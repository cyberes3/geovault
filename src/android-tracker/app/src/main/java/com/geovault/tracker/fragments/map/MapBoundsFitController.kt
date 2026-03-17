package com.geovault.tracker.fragments.map

import android.util.Log
import com.geovault.tracker.Tracker
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.pow

internal object MapBoundsFitController {
    fun moveCameraToFitBoundsWithMinZoomClamp(
        map: MapLibreMap,
        bounds: LatLngBounds,
        minZoom: Double,
        intent: CameraIntent?,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double,
        getBoundsPaddingEdgesPx: (Int) -> IntArray,
        applyMove: (CameraUpdate, CameraPaddingMode, CameraIntent?) -> Unit
    ) {
        val rawPadding = getBoundsPaddingEdgesPx(0)
        val p = MapCameraMath.sanitizeBoundsFitPaddingPx(
            mapWidthPxRaw = map.width.toInt(),
            mapHeightPxRaw = map.height.toInt(),
            rawPaddingPx = rawPadding,
            minViewportWidthFraction = minViewportWidthFraction,
            minViewportHeightFraction = minViewportHeightFraction
        )
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= minZoom) {
            applyMove(boundsUpdate, CameraPaddingMode.OVERLAY_AWARE, intent)
        } else {
            val center = bounds.center
            applyMove(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(minZoom)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                CameraPaddingMode.OVERLAY_AWARE,
                intent
            )
        }
    }

    fun moveCameraToFitBoundsCenteredWithMinZoomClamp(
        map: MapLibreMap,
        bounds: LatLngBounds,
        minZoom: Double,
        intent: CameraIntent? = null,
        applyMove: (CameraUpdate, CameraPaddingMode, CameraIntent?) -> Unit
    ) {
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 0, 0, 0, 0)
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= minZoom) {
            applyMove(boundsUpdate, CameraPaddingMode.CENTERED, intent)
        } else {
            val center = bounds.center
            applyMove(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(minZoom)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                CameraPaddingMode.CENTERED,
                intent
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
        tag: String,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double,
        getBoundsPaddingEdgesPx: (Int) -> IntArray,
        applyMove: (CameraUpdate, CameraPaddingMode, CameraIntent?) -> Unit
    ): Boolean {
        var preserveCentered = preserveCenteredAllTrackersFit
        if (fitToTrackerId != null) {
            preserveCentered = false
            val selectedTrackerPoint = coordsByTrackerId[fitToTrackerId]?.lastOrNull()
                ?: trackers.firstOrNull { it.id == fitToTrackerId }?.last_point
                    ?.takeIf { it.size >= 2 }
                    ?.let { lp -> LatLng(lp[1], lp[0]) }
            Log.d(
                tag,
                "all-trackers fit specific tracker path: fitToTrackerId=$fitToTrackerId, hasSelectedPoint=${selectedTrackerPoint != null}"
            )
            if (selectedTrackerPoint != null) {
                applyMove(
                    CameraUpdateFactory.newLatLngZoom(selectedTrackerPoint, trackerCardFocusZoom),
                    CameraPaddingMode.CENTERED,
                    CameraIntent.GROUP_MEMBER_FOCUS
                )
            } else {
                moveCameraToFitBoundsCenteredWithMinZoomClamp(
                    map = map,
                    bounds = bounds,
                    minZoom = minZoom,
                    intent = CameraIntent.BOUNDS_FIT,
                    applyMove = applyMove
                )
            }
            return preserveCentered
        }

        val repTrackerPoints = trackers.mapNotNull { t ->
            coordsByTrackerId[t.id]?.lastOrNull()?.let { t.id to it }
        }
        val rawPadding = getBoundsPaddingEdgesPx(0)
        val p = MapCameraMath.sanitizeBoundsFitPaddingPx(
            mapWidthPxRaw = map.width.toInt(),
            mapHeightPxRaw = map.height.toInt(),
            rawPaddingPx = rawPadding,
            minViewportWidthFraction = minViewportWidthFraction,
            minViewportHeightFraction = minViewportHeightFraction
        )
        val fitPaddingMode = CameraPaddingMode.OVERLAY_AWARE
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= minZoom) {
            preserveCentered = false
            Log.d(
                tag,
                "all-trackers fit all: zoom=${pos.zoom}, minZoom=$minZoom, trackerCount=${trackers.size}"
            )
            applyMove(boundsUpdate, fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }
        if (repTrackerPoints.size <= 1) {
            preserveCentered = false
            val target = repTrackerPoints.firstOrNull()?.second
                ?: trackers.firstNotNullOfOrNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
                ?: bounds.center
            Log.d(
                tag,
                "all-trackers fallback(single): representativeTrackerCount=${repTrackerPoints.size}, minZoom=$minZoom, target=(${target.latitude},${target.longitude})"
            )
            applyMove(CameraUpdateFactory.newLatLngZoom(target, minZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }

        preserveCentered = true
        val visibleW = (map.width - p[0] - p[2]).coerceAtLeast(1f).toDouble()
        val visibleH = (map.height - p[1] - p[3]).coerceAtLeast(1f).toDouble()
        val halfW = visibleW * 0.5
        val halfH = visibleH * 0.5
        val worldSize = 256.0 * 2.0.pow(minZoom)

        val pointsProjected = repTrackerPoints.map { (trackerId, pt) ->
            ProjectedTrackerPoint(
                trackerId = trackerId,
                latitude = pt.latitude,
                longitude = pt.longitude,
                worldX = MapCameraMath.worldXAtZoom(pt.longitude, minZoom),
                worldY = MapCameraMath.worldYAtZoom(pt.latitude, minZoom)
            )
        }
        val center = bounds.center
        val selection = BestEffortViewportSelector.select(
            points = pointsProjected,
            worldSize = worldSize,
            halfWidthPx = halfW,
            halfHeightPx = halfH,
            preferredCenterX = MapCameraMath.worldXAtZoom(center.longitude, minZoom),
            preferredCenterY = MapCameraMath.worldYAtZoom(center.latitude, minZoom)
        )
        val best = selection ?: run {
            preserveCentered = false
            val fallbackTarget = repTrackerPoints.first().second
            applyMove(CameraUpdateFactory.newLatLngZoom(fallbackTarget, minZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }
        val bestCenter = LatLng(
            MapCameraMath.worldYToLatDeg(best.centerY, worldSize),
            MapCameraMath.worldXToLonDeg(best.centerX, worldSize)
        )
        val includedTrackerIds = best.includedTrackerIds
        val bestCount = includedTrackerIds.size
        val excludedTrackerIds = repTrackerPoints.map { it.first }.filterNot { includedTrackerIds.contains(it) }
        Log.d(
            tag,
            "all-trackers fit-most: total=${repTrackerPoints.size}, included=${includedTrackerIds.size}, excluded=${excludedTrackerIds.size}, minZoom=$minZoom, visiblePx=(${visibleW.toInt()}x${visibleH.toInt()}), center=(${bestCenter.latitude},${bestCenter.longitude}), tieBreak=${best.tieBreakReason}, extentArea=${best.extentArea}, includedIds=$includedTrackerIds, excludedIds=$excludedTrackerIds"
        )
        if (bestCount <= 1) {
            preserveCentered = false
            val ordered = trackers.mapNotNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
            val target = ordered.firstOrNull() ?: repTrackerPoints.first().second
            Log.d(
                tag,
                "all-trackers fallback(one-at-min-zoom): bestCount=$bestCount, minZoom=$minZoom, target=(${target.latitude},${target.longitude})"
            )
            applyMove(CameraUpdateFactory.newLatLngZoom(target, minZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
        } else {
            applyMove(CameraUpdateFactory.newLatLngZoom(bestCenter, minZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
        }
        return preserveCentered
    }
}

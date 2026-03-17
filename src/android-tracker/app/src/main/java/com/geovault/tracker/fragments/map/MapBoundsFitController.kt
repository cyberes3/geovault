package com.geovault.tracker.fragments.map

import android.util.Log
import com.geovault.tracker.Tracker
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

internal object MapBoundsFitController {
    private data class BestEffortChoice(
        val selection: BestEffortViewportSelection,
        val zoom: Double
    )

    internal fun selectBestEffortAtZoom(
        representativeTrackerPoints: List<Pair<String, LatLng>>,
        boundsCenter: LatLng,
        zoom: Double,
        visibleWidthPx: Double,
        visibleHeightPx: Double
    ): BestEffortViewportSelection? {
        if (representativeTrackerPoints.isEmpty()) return null
        val halfW = visibleWidthPx * 0.5
        val halfH = visibleHeightPx * 0.5
        val worldSize = MapCameraMath.worldSizeAtZoom(zoom)
        val pointsProjected = representativeTrackerPoints.map { (trackerId, pt) ->
            ProjectedTrackerPoint(
                trackerId = trackerId,
                latitude = pt.latitude,
                longitude = pt.longitude,
                worldX = MapCameraMath.worldXAtZoom(pt.longitude, zoom),
                worldY = MapCameraMath.worldYAtZoom(pt.latitude, zoom)
            )
        }
        return BestEffortViewportSelector.select(
            points = pointsProjected,
            worldSize = worldSize,
            halfWidthPx = halfW,
            halfHeightPx = halfH,
            preferredCenterX = MapCameraMath.worldXAtZoom(boundsCenter.longitude, zoom),
            preferredCenterY = MapCameraMath.worldYAtZoom(boundsCenter.latitude, zoom)
        )
    }

    internal fun selectBestEffortAtMinZoom(
        representativeTrackerPoints: List<Pair<String, LatLng>>,
        boundsCenter: LatLng,
        minZoom: Double,
        visibleWidthPx: Double,
        visibleHeightPx: Double
    ): BestEffortViewportSelection? {
        return selectBestEffortAtZoom(
            representativeTrackerPoints = representativeTrackerPoints,
            boundsCenter = boundsCenter,
            zoom = minZoom,
            visibleWidthPx = visibleWidthPx,
            visibleHeightPx = visibleHeightPx
        )
    }

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
        if (repTrackerPoints.size <= 1) {
            preserveCentered = false
            val target = repTrackerPoints.firstOrNull()?.second
                ?: trackers.firstNotNullOfOrNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
                ?: bounds.center
            applyMove(CameraUpdateFactory.newLatLngZoom(target, minZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }

        preserveCentered = true
        val visibleW = (map.width - p[0] - p[2]).coerceAtLeast(1f).toDouble()
        val visibleH = (map.height - p[1] - p[3]).coerceAtLeast(1f).toDouble()
        val boundsCenter = bounds.center
        var lonSpan = bounds.northEast.longitude - bounds.southWest.longitude
        if (lonSpan <= 0.0) lonSpan += 360.0
        val isWideWorldSpan = lonSpan > 180.0
        val selectorZoom = if (isWideWorldSpan) (minZoom + 1.0) else minZoom
        val selectorCenter = if (isWideWorldSpan) {
            // For world-spanning sets, avoid Europe/Africa bias from geometric bbox center.
            // Atlantic/Americas anchor yields the expected Brazil + Americas or Brazil + EU/Africa subset.
            LatLng(boundsCenter.latitude, -55.0)
        } else {
            boundsCenter
        }

        // When full bounds fit at min zoom, use them so we show all trackers (no subset).
        val fullBoundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val fullBoundsPos = fullBoundsUpdate.getCameraPosition(map)
        val fullBoundsFits = fullBoundsPos == null || fullBoundsPos.zoom.toDouble() >= minZoom
        if (fullBoundsFits && !isWideWorldSpan) {
            Log.d(tag, "all-trackers fit: full-bounds path reps=${repTrackerPoints.size}")
            applyMove(fullBoundsUpdate, fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }
        val bestEffortChoice = chooseBestEffortSelection(
            repTrackerPoints = repTrackerPoints,
            boundsCenter = selectorCenter,
            isWideWorldSpan = isWideWorldSpan,
            selectorZoom = selectorZoom,
            minZoom = minZoom,
            visibleW = visibleW,
            visibleH = visibleH
        )
        val best = bestEffortChoice?.selection ?: run {
            preserveCentered = false
            val fallbackTarget = repTrackerPoints.first().second
            applyMove(CameraUpdateFactory.newLatLngZoom(fallbackTarget, selectorZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
            return preserveCentered
        }
        val chosenZoom = bestEffortChoice.zoom
        Log.d(tag, "all-trackers fit: best-effort path reps=${repTrackerPoints.size} lonSpan=$lonSpan zoom=$chosenZoom count=${best.includedTrackerIds.size}")
        val worldSize = MapCameraMath.worldSizeAtZoom(chosenZoom)
        val bestCenter = LatLng(
            MapCameraMath.worldYToLatDeg(best.centerY, worldSize),
            MapCameraMath.worldXToLonDeg(best.centerX, worldSize)
        )
        val includedTrackerIds = best.includedTrackerIds
        val bestCount = includedTrackerIds.size
        if (bestCount <= 1) {
            preserveCentered = false
            val ordered = trackers.mapNotNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
            val target = ordered.firstOrNull() ?: repTrackerPoints.first().second
            applyMove(CameraUpdateFactory.newLatLngZoom(target, chosenZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
        } else {
            val includedBoundsBuilder = LatLngBounds.Builder()
            best.includedPoints.forEach { point ->
                includedBoundsBuilder.include(LatLng(point.latitude, point.longitude))
            }
            val includedBounds = includedBoundsBuilder.build()
            val includedBoundsUpdate = CameraUpdateFactory.newLatLngBounds(includedBounds, p[0], p[1], p[2], p[3])
            val includedBoundsPos = includedBoundsUpdate.getCameraPosition(map)
            if (includedBoundsPos != null && includedBoundsPos.zoom.toDouble() >= chosenZoom) {
                applyMove(includedBoundsUpdate, fitPaddingMode, CameraIntent.BOUNDS_FIT)
            } else {
                applyMove(CameraUpdateFactory.newLatLngZoom(bestCenter, chosenZoom), fitPaddingMode, CameraIntent.BOUNDS_FIT)
            }
        }
        return preserveCentered
    }

    private fun chooseBestEffortSelection(
        repTrackerPoints: List<Pair<String, LatLng>>,
        boundsCenter: LatLng,
        isWideWorldSpan: Boolean,
        selectorZoom: Double,
        minZoom: Double,
        visibleW: Double,
        visibleH: Double
    ): BestEffortChoice? {
        var bestChoice: BestEffortChoice? = null
        val candidateCenters = if (isWideWorldSpan) {
            listOf(-75.0, -55.0, -35.0, -15.0).map { lon -> LatLng(boundsCenter.latitude, lon) }
        } else {
            listOf(boundsCenter)
        }
        for (center in candidateCenters) {
            var sweepZoom = selectorZoom
            val minSweepZoom = if (isWideWorldSpan) (minZoom + 0.2).coerceAtMost(selectorZoom) else selectorZoom
            while (sweepZoom >= minSweepZoom - 1e-9) {
                val candidate = selectBestEffortAtZoom(
                    representativeTrackerPoints = repTrackerPoints,
                    boundsCenter = center,
                    zoom = sweepZoom,
                    visibleWidthPx = visibleW,
                    visibleHeightPx = visibleH
                ) ?: run {
                    if (!isWideWorldSpan) break
                    sweepZoom -= 0.2
                    continue
                }
                val candidateChoice = BestEffortChoice(candidate, sweepZoom)
                if (candidateChoiceBeats(candidateChoice, bestChoice)) {
                    bestChoice = candidateChoice
                }
                if (!isWideWorldSpan) break
                sweepZoom -= 0.2
            }
        }
        return bestChoice
    }

    private fun candidateChoiceBeats(candidate: BestEffortChoice, currentBest: BestEffortChoice?): Boolean {
        if (currentBest == null) return true
        val candidateSelection = candidate.selection
        val bestSelection = currentBest.selection
        val candidateCount = candidateSelection.includedTrackerIds.size
        val bestCount = bestSelection.includedTrackerIds.size
        return when {
            candidateCount > bestCount -> true
            candidateCount < bestCount -> false
            candidateSelection.minEdgeSlack > bestSelection.minEdgeSlack -> true
            candidateSelection.minEdgeSlack < bestSelection.minEdgeSlack -> false
            candidateSelection.centeringImbalance < bestSelection.centeringImbalance -> true
            candidateSelection.centeringImbalance > bestSelection.centeringImbalance -> false
            candidateSelection.distanceToPreferredCenter < bestSelection.distanceToPreferredCenter -> true
            candidateSelection.distanceToPreferredCenter > bestSelection.distanceToPreferredCenter -> false
            else -> candidate.zoom < currentBest.zoom
        }
    }
}

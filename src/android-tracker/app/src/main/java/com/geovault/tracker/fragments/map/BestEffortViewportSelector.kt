package com.geovault.tracker.fragments.map

import kotlin.math.abs

internal data class ProjectedTrackerPoint(
    val trackerId: String,
    val latitude: Double,
    val longitude: Double,
    val worldX: Double,
    val worldY: Double
)

internal data class BestEffortViewportSelection(
    val centerX: Double,
    val centerY: Double,
    val includedTrackerIds: List<String>,
    val includedPoints: List<ProjectedTrackerPoint>,
    val extentArea: Double,
    val centeringImbalance: Double,
    val minEdgeSlack: Double,
    val distanceToPreferredCenter: Double,
    val tieBreakReason: String
)

internal object BestEffortViewportSelector {
    private const val INCLUSION_EPSILON_PX = 1e-6

    fun select(
        points: List<ProjectedTrackerPoint>,
        worldSize: Double,
        halfWidthPx: Double,
        halfHeightPx: Double,
        preferredCenterX: Double,
        preferredCenterY: Double
    ): BestEffortViewportSelection? {
        if (points.isEmpty()) return null

        val candidateXs = linkedSetOf<Double>()
        val candidateYs = linkedSetOf<Double>()
        for (point in points) {
            candidateXs.add(point.worldX)
            candidateXs.add(point.worldX - halfWidthPx)
            candidateXs.add(point.worldX + halfWidthPx)
            candidateYs.add(point.worldY)
            candidateYs.add(point.worldY - halfHeightPx)
            candidateYs.add(point.worldY + halfHeightPx)
        }
        candidateXs.add(preferredCenterX)
        candidateYs.add(preferredCenterY)

        val sortedXs = candidateXs.map { MapCameraMath.normalizeWrapped(it, worldSize) }.distinct().sorted()
        val sortedYs = candidateYs.map { clampViewportCenterY(it, halfHeightPx, worldSize) }.distinct().sorted()

        var best: BestEffortViewportSelection? = null
        var bestCoverage = -1
        var bestTieBreakReason = "initial"

        for (cx in sortedXs) {
            for (rawCy in sortedYs) {
                val cy = clampViewportCenterY(rawCy, halfHeightPx, worldSize)
                val included = points.filter { point ->
                    MapCameraMath.wrappedPixelDelta(point.worldX, cx, worldSize) <= halfWidthPx + INCLUSION_EPSILON_PX &&
                        abs(point.worldY - cy) <= halfHeightPx + INCLUSION_EPSILON_PX
                }
                val coverage = included.size
                val extentArea = computeExtentArea(included, cx, worldSize)
                val centering = computeCenteringMetrics(
                    included = included,
                    centerX = cx,
                    centerY = cy,
                    worldSize = worldSize,
                    halfWidthPx = halfWidthPx,
                    halfHeightPx = halfHeightPx
                )
                val distanceToPreferred = MapCameraMath.wrappedPixelDelta(cx, preferredCenterX, worldSize) +
                    abs(cy - preferredCenterY)
                val candidate = BestEffortViewportSelection(
                    centerX = cx,
                    centerY = cy,
                    includedTrackerIds = included.map { it.trackerId }.sorted(),
                    includedPoints = included,
                    extentArea = extentArea,
                    centeringImbalance = centering.first,
                    minEdgeSlack = centering.second,
                    distanceToPreferredCenter = distanceToPreferred,
                    tieBreakReason = "coverage"
                )
                if (candidateBeats(
                        candidate = candidate,
                        candidateCoverage = coverage,
                        best = best,
                        bestCoverage = bestCoverage
                    )
                ) {
                    bestTieBreakReason = when {
                        coverage > bestCoverage -> "coverage"
                        best != null && centering.first < best.centeringImbalance -> "centering"
                        best != null && centering.second > best.minEdgeSlack -> "edge-slack"
                        best != null && distanceToPreferred < best.distanceToPreferredCenter -> "center-proximity"
                        else -> "center-order"
                    }
                    best = candidate
                    bestCoverage = coverage
                }
            }
        }

        val bestSelection = best ?: return null
        val recentered = recenterWithinFeasibleRange(
            current = bestSelection,
            allPoints = points,
            worldSize = worldSize,
            halfWidthPx = halfWidthPx,
            halfHeightPx = halfHeightPx,
            preferredCenterX = preferredCenterX,
            preferredCenterY = preferredCenterY,
            requiredCoverage = bestCoverage
        )
        val recenteredApplied = recentered.centerX != bestSelection.centerX || recentered.centerY != bestSelection.centerY
        val finalTieBreakReason = if (recenteredApplied) {
            "$bestTieBreakReason+recenter"
        } else {
            bestTieBreakReason
        }
        return recentered.copy(tieBreakReason = finalTieBreakReason)
    }

    private fun recenterWithinFeasibleRange(
        current: BestEffortViewportSelection,
        allPoints: List<ProjectedTrackerPoint>,
        worldSize: Double,
        halfWidthPx: Double,
        halfHeightPx: Double,
        preferredCenterX: Double,
        preferredCenterY: Double,
        requiredCoverage: Int
    ): BestEffortViewportSelection {
        if (current.includedPoints.size <= 1) return current

        val anchorX = current.centerX
        val includedUnwrappedX = current.includedPoints.map { pt ->
            anchorX + signedWrappedDelta(pt.worldX, anchorX, worldSize)
        }
        val minX = includedUnwrappedX.minOrNull() ?: return current
        val maxX = includedUnwrappedX.maxOrNull() ?: return current
        val minY = current.includedPoints.minOf { it.worldY }
        val maxY = current.includedPoints.maxOf { it.worldY }

        var feasibleMinX = Double.NEGATIVE_INFINITY
        var feasibleMaxX = Double.POSITIVE_INFINITY
        for (x in includedUnwrappedX) {
            feasibleMinX = maxOf(feasibleMinX, x - halfWidthPx)
            feasibleMaxX = minOf(feasibleMaxX, x + halfWidthPx)
        }
        if (feasibleMinX > feasibleMaxX) return current

        val minCenterY = if (halfHeightPx >= worldSize * 0.5) worldSize * 0.5 else halfHeightPx
        val maxCenterY = if (halfHeightPx >= worldSize * 0.5) worldSize * 0.5 else worldSize - halfHeightPx
        var feasibleMinY = minCenterY
        var feasibleMaxY = maxCenterY
        for (y in current.includedPoints.map { it.worldY }) {
            feasibleMinY = maxOf(feasibleMinY, y - halfHeightPx)
            feasibleMaxY = minOf(feasibleMaxY, y + halfHeightPx)
        }
        if (feasibleMinY > feasibleMaxY) return current

        val balancedX = ((minX + maxX) * 0.5).coerceIn(feasibleMinX, feasibleMaxX)
        val balancedY = ((minY + maxY) * 0.5).coerceIn(feasibleMinY, feasibleMaxY)
        val preferredUnwrappedX = anchorX + signedWrappedDelta(preferredCenterX, anchorX, worldSize)
        val preferredX = preferredUnwrappedX.coerceIn(feasibleMinX, feasibleMaxX)
        val preferredY = preferredCenterY.coerceIn(feasibleMinY, feasibleMaxY)

        val candidateCenters = linkedSetOf<Pair<Double, Double>>(
            balancedX to balancedY,
            preferredX to preferredY,
            current.centerX to current.centerY,
            feasibleMinX to balancedY,
            feasibleMaxX to balancedY,
            balancedX to feasibleMinY,
            balancedX to feasibleMaxY
        )
        val currentIds = current.includedTrackerIds.toSet()
        val coverageCandidates = candidateCenters.map { (cx, cy) ->
            buildSelectionForCenter(
                allPoints = allPoints,
                centerX = cx,
                centerY = cy,
                worldSize = worldSize,
                halfWidthPx = halfWidthPx,
                halfHeightPx = halfHeightPx,
                preferredCenterX = preferredCenterX,
                preferredCenterY = preferredCenterY
            )
        }.filter { candidate ->
            candidate.includedPoints.size >= requiredCoverage &&
                candidate.includedTrackerIds.toSet() == currentIds
        }

        if (coverageCandidates.isEmpty()) return current
        return coverageCandidates.minWithOrNull(bestSelectionComparator()) ?: current
    }

    private fun signedWrappedDelta(a: Double, b: Double, worldSize: Double): Double {
        var d = (a - b) % worldSize
        if (d < -worldSize * 0.5) d += worldSize
        if (d >= worldSize * 0.5) d -= worldSize
        return d
    }

    private fun computeCenteringMetrics(
        included: List<ProjectedTrackerPoint>,
        centerX: Double,
        centerY: Double,
        worldSize: Double,
        halfWidthPx: Double,
        halfHeightPx: Double
    ): Pair<Double, Double> {
        if (included.isEmpty()) return Double.POSITIVE_INFINITY to Double.NEGATIVE_INFINITY
        val signedDxs = included.map { signedWrappedDelta(it.worldX, centerX, worldSize) }
        val minDx = signedDxs.minOrNull() ?: 0.0
        val maxDx = signedDxs.maxOrNull() ?: 0.0
        val minDy = included.minOf { it.worldY - centerY }
        val maxDy = included.maxOf { it.worldY - centerY }
        val leftSlack = halfWidthPx + minDx
        val rightSlack = halfWidthPx - maxDx
        val topSlack = halfHeightPx + minDy
        val bottomSlack = halfHeightPx - maxDy
        val imbalance = abs(leftSlack - rightSlack) + abs(topSlack - bottomSlack)
        val minSlack = minOf(leftSlack, rightSlack, topSlack, bottomSlack)
        return imbalance to minSlack
    }

    private fun bestSelectionComparator(): Comparator<BestEffortViewportSelection> {
        return compareBy<BestEffortViewportSelection> { it.centeringImbalance }
            .thenByDescending { it.minEdgeSlack }
            .thenBy { it.distanceToPreferredCenter }
            .thenBy { it.extentArea }
            .thenBy { it.centerX }
            .thenBy { it.centerY }
    }

    private fun candidateBeats(
        candidate: BestEffortViewportSelection,
        candidateCoverage: Int,
        best: BestEffortViewportSelection?,
        bestCoverage: Int
    ): Boolean {
        if (best == null) return true
        if (candidateCoverage != bestCoverage) return candidateCoverage > bestCoverage
        if (candidate.centeringImbalance != best.centeringImbalance) {
            return candidate.centeringImbalance < best.centeringImbalance
        }
        if (candidate.minEdgeSlack != best.minEdgeSlack) return candidate.minEdgeSlack > best.minEdgeSlack
        if (candidate.distanceToPreferredCenter != best.distanceToPreferredCenter) {
            return candidate.distanceToPreferredCenter < best.distanceToPreferredCenter
        }
        if (candidate.extentArea != best.extentArea) return candidate.extentArea < best.extentArea
        if (candidate.centerX != best.centerX) return candidate.centerX < best.centerX
        if (candidate.centerY != best.centerY) return candidate.centerY < best.centerY
        return false
    }

    private fun clampViewportCenterY(centerY: Double, halfHeightPx: Double, worldSize: Double): Double {
        if (halfHeightPx >= worldSize * 0.5) return worldSize * 0.5
        val minCenterY = halfHeightPx
        val maxCenterY = worldSize - halfHeightPx
        return centerY.coerceIn(minCenterY, maxCenterY)
    }

    private fun computeExtentArea(
        included: List<ProjectedTrackerPoint>,
        centerX: Double,
        worldSize: Double
    ): Double {
        if (included.isEmpty()) return Double.POSITIVE_INFINITY
        val xRadius = included.maxOf { MapCameraMath.wrappedPixelDelta(it.worldX, centerX, worldSize) }
        val minY = included.minOf { it.worldY }
        val maxY = included.maxOf { it.worldY }
        val width = xRadius * 2.0
        val height = maxY - minY
        return width * height
    }

    private fun buildSelectionForCenter(
        allPoints: List<ProjectedTrackerPoint>,
        centerX: Double,
        centerY: Double,
        worldSize: Double,
        halfWidthPx: Double,
        halfHeightPx: Double,
        preferredCenterX: Double,
        preferredCenterY: Double
    ): BestEffortViewportSelection {
        val normalizedX = MapCameraMath.normalizeWrapped(centerX, worldSize)
        val clampedY = clampViewportCenterY(centerY, halfHeightPx, worldSize)
        val included = allPoints.filter { point ->
            MapCameraMath.wrappedPixelDelta(point.worldX, normalizedX, worldSize) <= halfWidthPx + INCLUSION_EPSILON_PX &&
                abs(point.worldY - clampedY) <= halfHeightPx + INCLUSION_EPSILON_PX
        }
        val extentArea = computeExtentArea(included, normalizedX, worldSize)
        val centering = computeCenteringMetrics(
            included = included,
            centerX = normalizedX,
            centerY = clampedY,
            worldSize = worldSize,
            halfWidthPx = halfWidthPx,
            halfHeightPx = halfHeightPx
        )
        val distanceToPreferred = MapCameraMath.wrappedPixelDelta(normalizedX, preferredCenterX, worldSize) +
            abs(clampedY - preferredCenterY)
        return BestEffortViewportSelection(
            centerX = normalizedX,
            centerY = clampedY,
            includedTrackerIds = included.map { it.trackerId }.sorted(),
            includedPoints = included,
            extentArea = extentArea,
            centeringImbalance = centering.first,
            minEdgeSlack = centering.second,
            distanceToPreferredCenter = distanceToPreferred,
            tieBreakReason = "coverage"
        )
    }
}

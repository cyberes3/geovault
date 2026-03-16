package com.geovault.tracker.fragments.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

internal object MapCameraMath {
    fun sanitizeBoundsFitPaddingPx(
        mapWidthPxRaw: Int,
        mapHeightPxRaw: Int,
        rawPaddingPx: IntArray,
        minViewportWidthFraction: Double,
        minViewportHeightFraction: Double
    ): IntArray {
        val mapWidthPx = mapWidthPxRaw.coerceAtLeast(1)
        val mapHeightPx = mapHeightPxRaw.coerceAtLeast(1)
        val minViewportWidthPx = (mapWidthPx * minViewportWidthFraction).toInt().coerceAtLeast(1)
        val minViewportHeightPx = (mapHeightPx * minViewportHeightFraction).toInt().coerceAtLeast(1)
        val maxTotalHorizontalInsetPx = (mapWidthPx - minViewportWidthPx).coerceAtLeast(0)
        val maxTotalVerticalInsetPx = (mapHeightPx - minViewportHeightPx).coerceAtLeast(0)
        val maxSideHorizontalInsetPx = (maxTotalHorizontalInsetPx / 2).coerceAtLeast(0)
        val maxSideVerticalInsetPx = (maxTotalVerticalInsetPx / 2).coerceAtLeast(0)

        var leftPx = rawPaddingPx[0].coerceIn(0, maxSideHorizontalInsetPx)
        var topPx = rawPaddingPx[1].coerceIn(0, maxSideVerticalInsetPx)
        var rightPx = rawPaddingPx[2].coerceIn(0, maxSideHorizontalInsetPx)
        var bottomPx = rawPaddingPx[3].coerceIn(0, maxSideVerticalInsetPx)

        val horizontalInsetPx = leftPx + rightPx
        if (horizontalInsetPx > maxTotalHorizontalInsetPx && horizontalInsetPx > 0) {
            leftPx = ((leftPx.toLong() * maxTotalHorizontalInsetPx) / horizontalInsetPx).toInt()
            rightPx = (maxTotalHorizontalInsetPx - leftPx).coerceAtLeast(0)
        }

        val verticalInsetPx = topPx + bottomPx
        if (verticalInsetPx > maxTotalVerticalInsetPx && verticalInsetPx > 0) {
            topPx = ((topPx.toLong() * maxTotalVerticalInsetPx) / verticalInsetPx).toInt()
            bottomPx = (maxTotalVerticalInsetPx - topPx).coerceAtLeast(0)
        }

        return intArrayOf(leftPx, topPx, rightPx, bottomPx)
    }

    fun worldXAtZoom(lonDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        var norm = ((lonDeg + 180.0) / 360.0) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * worldSize
    }

    fun worldYAtZoom(latDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        val lat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val latRad = lat * kotlin.math.PI / 180.0
        val mercN = ln(tan(kotlin.math.PI / 4.0 + latRad / 2.0))
        return (0.5 - mercN / (2.0 * kotlin.math.PI)) * worldSize
    }

    fun wrappedPixelDelta(a: Double, b: Double, worldSize: Double): Double {
        val d = abs(a - b)
        return min(d, worldSize - d)
    }

    fun worldXToLonDeg(x: Double, worldSize: Double): Double {
        var norm = (x / worldSize) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * 360.0 - 180.0
    }

    fun worldYToLatDeg(y: Double, worldSize: Double): Double {
        val yy = y.coerceIn(0.0, worldSize)
        val n = kotlin.math.PI * (1.0 - 2.0 * yy / worldSize)
        return atan(sinh(n)) * 180.0 / kotlin.math.PI
    }

    /** Converts a bbox array [minLon, minLat, maxLon, maxLat] to LatLngBounds, or null if invalid. */
    fun bboxToLatLngBounds(bbox: List<*>?): LatLngBounds? {
        if (bbox == null || bbox.size != 4) return null
        val minLon = (bbox[0] as? Number)?.toDouble() ?: return null
        val minLat = (bbox[1] as? Number)?.toDouble() ?: return null
        val maxLon = (bbox[2] as? Number)?.toDouble() ?: return null
        val maxLat = (bbox[3] as? Number)?.toDouble() ?: return null
        return LatLngBounds.Builder()
            .include(LatLng(minLat, minLon))
            .include(LatLng(maxLat, maxLon))
            .build()
    }
}

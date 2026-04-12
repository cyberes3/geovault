package com.geovault.tracker.presentation

import com.geovault.common.maps.location.AccuracyGeometryBuilder
import com.geovault.common.maps.location.AccuracyRadiusInput
import com.geovault.common.maps.location.AccuracyRadiusPolicy
import com.geovault.common.maps.location.LatLon
import com.geovault.common.maps.render.MapRenderPolygon

data class TrackerAccuracyCircleInput(
    val polygonId: String,
    val trackerId: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val streamedAccuracyMeters: Float?,
    val fallbackAccuracyMeters: Float?,
    val allowFallback: Boolean,
    val colorHex: String,
)

class TrackerAccuracyPolygonFactory {
    fun create(input: TrackerAccuracyCircleInput): MapRenderPolygon? {
        val radiusMeters = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = input.streamedAccuracyMeters,
                fallbackAccuracyMeters = input.fallbackAccuracyMeters,
                allowFallback = input.allowFallback,
            )
        )
        if (radiusMeters <= 0.0) return null
        val ring = AccuracyGeometryBuilder.buildAccuracyRing(
            center = LatLon(input.centerLatitude, input.centerLongitude),
            radiusMeters = radiusMeters,
        )
        if (ring.isEmpty()) return null
        val fillColorHex = withAlpha(input.colorHex, 0x40)
        return MapRenderPolygon(
            id = input.polygonId,
            rings = listOf(ring.map { it.lat to it.lon }),
            fillColorHex = fillColorHex,
            outlineColorHex = fillColorHex,
        )
    }

    private fun withAlpha(colorHex: String, alpha: Int): String {
        val normalized = colorHex.removePrefix("#")
        val safeHex = if (normalized.length == 6) normalized else TrackerMapIconIds.DEFAULT_COLOR_HEX.removePrefix("#")
        val r = safeHex.substring(0, 2).toInt(16)
        val g = safeHex.substring(2, 4).toInt(16)
        val b = safeHex.substring(4, 6).toInt(16)
        val a = alpha.coerceIn(0, 255) / 255f
        return "rgba($r,$g,$b,$a)"
    }
}

class TrackerAccuracyCircleResolver(
    private val polygonFactory: TrackerAccuracyPolygonFactory = TrackerAccuracyPolygonFactory(),
) {
    fun buildPolygons(inputs: List<TrackerAccuracyCircleInput>): List<MapRenderPolygon> {
        return inputs
            .sortedBy { it.polygonId }
            .mapNotNull { polygonFactory.create(it) }
    }
}

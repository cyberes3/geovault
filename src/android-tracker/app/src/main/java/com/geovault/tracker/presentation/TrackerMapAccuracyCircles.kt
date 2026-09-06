package com.geovault.tracker.presentation

import com.geovault.common.ui.theme.GeoVaultColorHex
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
    val sourceAccuracyMeters: Float?,
    val fallbackAccuracyMeters: Float?,
    val allowFallback: Boolean,
    val colorHex: String,
)

class TrackerAccuracyPolygonFactory {
    fun create(input: TrackerAccuracyCircleInput): MapRenderPolygon? {
        val radiusMeters = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = input.sourceAccuracyMeters,
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
        val fillColorHex = GeoVaultColorHex.toRgbaCss(
            hex = input.colorHex,
            alphaByte = 0x40,
            fallbackHex = TrackerMapIconIds.DEFAULT_COLOR_HEX,
        )
        return MapRenderPolygon(
            id = input.polygonId,
            rings = listOf(ring.map { it.lat to it.lon }),
            fillColorHex = fillColorHex,
            outlineColorHex = fillColorHex,
        )
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

package com.geovault.tracker.fragments.map

import android.graphics.PointF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.sqrt

internal object MapTapSelectionHandler {
    fun selectNearestFeature(
        map: MapLibreMap,
        tapPoint: PointF,
        features: List<Feature>
    ): Feature? {
        if (features.isEmpty()) return null
        if (features.size == 1) return features[0]
        return features.minByOrNull { feature ->
            val geom = feature.geometry()
            if (geom !is Point) return@minByOrNull Float.MAX_VALUE
            val screen = map.projection.toScreenLocation(LatLng(geom.latitude(), geom.longitude()))
            val dx = screen.x - tapPoint.x
            val dy = screen.y - tapPoint.y
            sqrt(dx * dx + dy * dy)
        } ?: features[0]
    }

    fun isTapNearPoint(
        map: MapLibreMap,
        tapLatLng: LatLng,
        point: LatLng,
        maxDistancePx: Float
    ): Boolean {
        val tapScreen = map.projection.toScreenLocation(tapLatLng)
        val pointScreen = map.projection.toScreenLocation(point)
        val dx = pointScreen.x - tapScreen.x
        val dy = pointScreen.y - tapScreen.y
        return sqrt(dx * dx + dy * dy) <= maxDistancePx
    }
}

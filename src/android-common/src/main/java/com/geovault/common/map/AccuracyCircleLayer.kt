package com.geovault.common.map

import android.graphics.Color
import androidx.core.content.ContextCompat
import com.geovault.common.R
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Helper to add a circle layer that visualizes position accuracy (radius in meters)
 * from a GeoJSON point source. The source must have point features with a numeric
 * "accuracy" property (meters). The circle scales with zoom; call [updateRadiusFromCamera]
 * from [MapLibreMap.addOnCameraMoveListener] so the radius updates when the user zooms.
 *
 * Usage:
 * 1. Add your GeoJSON source with point feature(s) that include "accuracy" (meters).
 * 2. Call [attachToStyle] with that source id and optional styling.
 * 3. Register [updateRadiusFromCamera] with [MapLibreMap.addOnCameraMoveListener].
 */
object AccuracyCircleLayer {

    /**
     * Default fill: semi-transparent blue. Default stroke: primary blue. Override via [Options].
     */
    data class Options(
        val fillColor: Int = Color.argb(64, 51, 136, 255),
        val strokeColor: Int = 0, // 0 = use context theme primary (set in attach if needed)
        val strokeWidth: Float = 1f,
        val minRadiusPx: Float = 6f
    )

    /**
     * Computes pixels-per-meter at the given zoom and latitude (Web Mercator).
     * Use this to drive the circle radius so it represents real-world meters.
     */
    fun pixelsPerMeterAt(zoom: Double, latitude: Double): Double {
        return (256.0 * Math.pow(2.0, zoom)) /
            (40075016.686 * kotlin.math.cos(latitude * Math.PI / 180.0)).coerceAtLeast(1.0)
    }

    /**
     * Adds a circle layer that draws a circle of radius `accuracy` meters around each
     * point in [sourceId]. Points with no "accuracy" or accuracy &lt;= 0 are filtered out.
     *
     * @param style The map style (must already contain the source).
     * @param sourceId Id of the GeoJSON source whose points have an "accuracy" number property (meters).
     * @param layerId Id for the new circle layer.
     * @param initialPixelsPerMeter Pixels per meter at current camera (use [pixelsPerMeterAt] from map camera).
     * @param options Fill/stroke colors and min radius; pass [strokeColor] 0 to use [Context] theme primary (only if [context] is non-null).
     */
    fun attachToStyle(
        style: Style,
        sourceId: String,
        layerId: String,
        initialPixelsPerMeter: Double,
        options: Options = Options(),
        context: android.content.Context? = null
    ) {
        if (style.getLayer(layerId) != null) return
        val strokeColor = if (options.strokeColor != 0) options.strokeColor
        else (context?.let { ContextCompat.getColor(it, R.color.gv_common_primary_blue) } ?: Color.parseColor("#163D8A"))
        val layer = CircleLayer(layerId, sourceId).apply {
            setFilter(Expression.gt(Expression.get("accuracy"), Expression.literal(0)))
            setProperties(
                PropertyFactory.circleRadius(
                    Expression.max(
                        Expression.literal(options.minRadiusPx),
                        Expression.product(
                            Expression.get("accuracy"),
                            Expression.literal(initialPixelsPerMeter)
                        )
                    )
                ),
                PropertyFactory.circleColor(options.fillColor),
                PropertyFactory.circleStrokeColor(strokeColor),
                PropertyFactory.circleStrokeWidth(options.strokeWidth)
            )
        }
        style.addLayer(layer)
    }

    /**
     * Updates the circle layer's radius expression using the current camera zoom/lat.
     * Call this from [MapLibreMap.addOnCameraMoveListener] so the circle scales correctly when the user zooms.
     *
     * @param minRadiusPx Should match [Options.minRadiusPx] used in [attachToStyle] (default 6f).
     */
    fun updateRadiusFromCamera(map: MapLibreMap, layerId: String, minRadiusPx: Float = 6f) {
        val style = map.style ?: return
        val layer = style.getLayer(layerId) as? CircleLayer ?: return
        val zoom = map.cameraPosition?.zoom ?: return
        val lat = map.cameraPosition?.target?.latitude ?: 0.0
        val pixelsPerMeter = pixelsPerMeterAt(zoom, lat)
        layer.setProperties(
            PropertyFactory.circleRadius(
                Expression.max(
                    Expression.literal(minRadiusPx),
                    Expression.product(
                        Expression.get("accuracy"),
                        Expression.literal(pixelsPerMeter)
                    )
                )
            )
        )
    }
}

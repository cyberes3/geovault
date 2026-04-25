package com.geovault.common.maps.core

import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Three stacked [LineLayer]s (bottom → top): wide **black** outer ring, **white** mid ring,
 * then data-driven stroke color on top — matches survey / tracker pin treatment (dark edge
 * outside, light halo, colored core).
 */
object OutlinedGeoJsonLineLayers {
    const val PROPERTY_LINE_COLOR = "lineColor"
    const val WIDTH_OUTER_PX = 6f
    const val WIDTH_BORDER_PX = 5f
    const val WIDTH_FILL_PX = 3f

    fun createOuterLayer(
        layerId: String,
        sourceId: String,
        visibility: String = Property.VISIBLE,
    ): LineLayer {
        return LineLayer(layerId, sourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(WIDTH_OUTER_PX),
                PropertyFactory.lineColor(GeoVaultColorTokens.MapLineworkBorder.toArgb()),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.visibility(visibility),
            )
        }
    }

    fun createBorderLayer(
        layerId: String,
        sourceId: String,
        visibility: String = Property.VISIBLE,
    ): LineLayer {
        return LineLayer(layerId, sourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(WIDTH_BORDER_PX),
                PropertyFactory.lineColor(GeoVaultColorTokens.MapLineworkHalo.toArgb()),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.visibility(visibility),
            )
        }
    }

    fun createFillLayer(
        layerId: String,
        sourceId: String,
        visibility: String = Property.VISIBLE,
    ): LineLayer {
        return LineLayer(layerId, sourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(WIDTH_FILL_PX),
                PropertyFactory.lineColor(Expression.get(PROPERTY_LINE_COLOR)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.visibility(visibility),
            )
        }
    }

    fun colorIntToHex6(@ColorInt color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}

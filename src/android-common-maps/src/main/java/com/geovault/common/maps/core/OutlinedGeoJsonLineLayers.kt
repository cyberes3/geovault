package com.geovault.common.maps.core

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.geovault.common.R as CommonR
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

object OutlinedGeoJsonLineLayers {
    const val PROPERTY_LINE_COLOR = "lineColor"
    const val PROPERTY_OUTLINE_COLOR = "outlineColor"
    const val WIDTH_OUTER_PX = 6f
    const val WIDTH_BORDER_PX = 5f
    const val WIDTH_FILL_PX = 3f

    fun createOuterLayer(
        layerId: String,
        sourceId: String,
        context: Context? = null,
        visibility: String = Property.VISIBLE,
    ): LineLayer {
        val outerColor = context?.let {
            ContextCompat.getColor(it, CommonR.color.gv_common_map_linework_outer_halo)
        } ?: 0xFFFFFFFF.toInt()
        return LineLayer(layerId, sourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(WIDTH_OUTER_PX),
                PropertyFactory.lineColor(outerColor),
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
                PropertyFactory.lineColor(Expression.get(PROPERTY_OUTLINE_COLOR)),
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

    fun borderColorHex(context: Context): String = colorResToHex6(context, CommonR.color.gv_common_map_linework_border)

    fun lineColorHex(context: Context, @ColorRes colorResId: Int): String = colorResToHex6(context, colorResId)

    fun colorIntToHex6(@ColorInt color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun colorResToHex6(context: Context, @ColorRes colorResId: Int): String {
        return colorIntToHex6(ContextCompat.getColor(context, colorResId))
    }
}

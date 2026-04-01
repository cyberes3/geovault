package com.geovault.common.maps.render

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.geovault.common.R as CommonUiR

data class MapMarkerStyle(
    @param:ColorInt val outerBorderColorInt: Int,
    @param:ColorInt val innerBorderColorInt: Int,
    @param:ColorInt val centerColorInt: Int,
)

enum class MapMarkerBorderStyle {
    LIGHT,
    DARK,
}

object CommonMapMarkerStyles {
    fun fromCenterColorHex(centerColorHex: String, borderStyle: MapMarkerBorderStyle): MapMarkerStyle {
        return fromCenterColorInt(parseColorHex(centerColorHex), borderStyle)
    }

    fun fromCenterColorInt(@ColorInt centerColorInt: Int, borderStyle: MapMarkerBorderStyle): MapMarkerStyle {
        val (outerBorderColorInt, innerBorderColorInt) = when (borderStyle) {
            MapMarkerBorderStyle.LIGHT -> 0xFF000000.toInt() to 0xFFFFFFFF.toInt()
            MapMarkerBorderStyle.DARK -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        }
        return MapMarkerStyle(
            outerBorderColorInt = outerBorderColorInt,
            innerBorderColorInt = innerBorderColorInt,
            centerColorInt = centerColorInt,
        )
    }

    fun default(context: Context): MapMarkerStyle {
        return fromCenterColorInt(
            centerColorInt = ContextCompat.getColor(context, CommonUiR.color.gv_common_main_blue),
            borderStyle = MapMarkerBorderStyle.LIGHT,
        )
    }

    fun selected(context: Context): MapMarkerStyle {
        return fromCenterColorInt(
            centerColorInt = ContextCompat.getColor(context, CommonUiR.color.gv_common_main_yellow),
            borderStyle = MapMarkerBorderStyle.DARK,
        )
    }

    private fun parseColorHex(value: String): Int {
        val normalized = value.removePrefix("#")
        return when (normalized.length) {
            6 -> (0xFF000000L or normalized.toLong(16)).toInt()
            8 -> normalized.toLong(16).toInt()
            else -> throw IllegalArgumentException("Expected hex color in #RRGGBB or #AARRGGBB format.")
        }
    }
}

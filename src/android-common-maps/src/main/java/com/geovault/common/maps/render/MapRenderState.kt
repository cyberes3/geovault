package com.geovault.common.maps.render

import com.geovault.common.ui.theme.GeoVaultColorTokens

data class MapRenderPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val iconImageId: String? = null,
    /** Clockwise degrees for [org.maplibre.android.style.layers.PropertyFactory.iconRotate]. */
    val iconRotationDegrees: Float? = null,
    val pointRadius: Float? = null,
    val pointFillColorHex: String? = null,
    val pointStrokeColorHex: String? = null,
    val pointStrokeWidth: Float? = null,
    val labelTextColorHex: String? = null,
    val labelTextSize: Float? = null,
    val iconSize: Float? = null,
)

data class MapRenderLine(
    val id: String,
    val coordinates: List<Pair<Double, Double>>,
    val lineColorHex: String,
)

data class MapRenderPolygon(
    val id: String,
    val rings: List<List<Pair<Double, Double>>>,
    val fillColorHex: String = GeoVaultColorTokens.Hex.ScrimMedium,
    val outlineColorHex: String = GeoVaultColorTokens.Hex.MapLineworkBorder,
)

data class MapRenderState(
    val points: List<MapRenderPoint> = emptyList(),
    val lines: List<MapRenderLine> = emptyList(),
    val polygons: List<MapRenderPolygon> = emptyList(),
)

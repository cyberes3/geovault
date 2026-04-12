package com.geovault.common.maps.render

data class GeoJsonRenderConfig(
    val belowLayerId: String? = null,
    /**
     * When true, [GeoJsonRenderPlugin.setRenderState] must be invoked on the main looper and
     * GeoJSON sources are updated immediately on that call. When false, updates posted from
     * background threads are marshaled to the main looper (still async relative to the caller).
     */
    val synchronousGeoJsonApplication: Boolean = false,
    val showPointCircles: Boolean = true,
    val showPointLabelsAndIcons: Boolean = true,
    val showPointTextLabels: Boolean = true,
    val renderPointSymbolsAboveLines: Boolean = false,
    val useSynchronousSourceUpdates: Boolean = false,
    val disablePointSymbolFade: Boolean = false,
    val markerStyles: Map<String, MapMarkerStyle> = emptyMap(),
    val showPolygonFill: Boolean = true,
    val showPolygonOutline: Boolean = true,
    val defaultPointRadius: Float = 6f,
    val defaultPointFillColorHex: String = "#0077FF",
    val defaultPointStrokeColorHex: String = "#FFFFFF",
    val defaultPointStrokeWidth: Float = 1.5f,
    val defaultLabelTextColorHex: String = "#1D1D1D",
    val defaultLabelTextSize: Float = 12f,
    val defaultIconSize: Float = 1f,
    val defaultPolygonFillOpacity: Float = 0.35f,
    val defaultPolygonOutlineWidth: Float = 2f,
)

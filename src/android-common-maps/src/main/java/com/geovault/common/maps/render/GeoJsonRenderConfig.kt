package com.geovault.common.maps.render

import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Configuration for [GeoJsonRenderPlugin].
 *
 * **Point icons and text labels:** Whenever [showPointLabelsAndIcons] is true and
 * [showPointTextLabels] is true, the plugin **automatically** registers two MapLibre symbol
 * layers on the same GeoJSON source—`{sourceIdPrefix}-points-icon-layer` (visible markers) and
 * `{sourceIdPrefix}-points-label-layer` (text with a zero-opacity icon for anchoring)—so labels
 * hide under collision with other markers while icons stay visible. Apps do not opt in to
 * this; it is the default map behavior for labeled points. When [showPointTextLabels] is false,
 * only the icon layer is created (e.g. icon-only maps).
 */
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
    /**
     * When true (the default), point symbol icon and text use zero-duration opacity transitions
     * so marker updates and collision-driven label visibility stay instant instead of fading.
     * Set to false only if you deliberately want MapLibre’s default symbol opacity transitions.
     */
    val disablePointSymbolFade: Boolean = true,
    val markerStyles: Map<String, MapMarkerStyle> = emptyMap(),
    val showPolygonFill: Boolean = true,
    val showPolygonOutline: Boolean = true,
    val defaultPointRadius: Float = 6f,
    val defaultPointFillColorHex: String = GeoVaultColorTokens.Hex.MapPointDefault,
    val defaultPointStrokeColorHex: String = GeoVaultColorTokens.Hex.Surface,
    val defaultPointStrokeWidth: Float = 1.5f,
    val defaultLabelTextColorHex: String = GeoVaultColorTokens.Hex.MapLabelText,
    val defaultLabelTextSize: Float = 12f,
    val defaultIconSize: Float = 1f,
    val defaultPolygonFillOpacity: Float = 0.35f,
    val defaultPolygonOutlineWidth: Float = 2f,
)

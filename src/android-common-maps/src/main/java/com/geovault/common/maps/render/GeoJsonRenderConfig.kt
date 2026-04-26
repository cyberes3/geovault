package com.geovault.common.maps.render

import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.style.layers.Property

/**
 * Configuration for [GeoJsonRenderPlugin].
 *
 * **Point icons and text labels:** Whenever [showPointLabelsAndIcons] is true and
 * [showPointTextLabels] is true, the plugin **automatically** registers two MapLibre symbol
 * layers on the same GeoJSON source—`{sourceIdPrefix}-points-label-layer` (text with a
 * zero-opacity icon for anchoring) is added **below** `{sourceIdPrefix}-points-icon-layer`
 * (visible markers), so icons always paint on top of names. Label collision still hides
 * overlapping labels ([showPointTextLabels] layer uses MapLibre collision), not icons.
 * When [showPointTextLabels] is false, only the icon layer is created (e.g. icon-only maps).
 *
 * **Text halo:** [pointLabelHaloWidth] optionally draws an outline around point name labels
 * (e.g. on satellite or busy imagery) so names stay legible. When [pointLabelHaloWidth] is
 * positive and [pointLabelHaloColorArgb] is null, [GeoJsonRenderPlugin] uses
 * [com.geovault.common.ui.theme.GeoVaultColorTokens.MapLineworkHalo].
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
    val pointClustering: GeoJsonPointClusteringConfig? = null,
    /**
     * When [pointClustering] is enabled, features whose [MapRenderPoint.iconImageId] is in this
     * set are **not** written to the clustered source. They are drawn from a second, non-clustered
     * GeoJSON source and stacked on top of the main point layers so e.g. a navigation target pin
     * is never absorbed into a cluster.
     */
    val iconImageIdsExcludedFromClustering: Set<String> = emptySet(),
    val useSynchronousSourceUpdates: Boolean = false,
    /**
     * When true (the default), point symbol icon and text use zero-duration opacity transitions
     * so marker updates and collision-driven label visibility stay instant instead of fading.
     * Set to false only if you deliberately want MapLibre’s default symbol opacity transitions.
     */
    val disablePointSymbolFade: Boolean = true,
    val markerStyles: Map<String, MapMarkerStyle> = emptyMap(),
    val symbolIconStyles: Map<String, MapSymbolIconStyle> = emptyMap(),
    val showPolygonFill: Boolean = true,
    val showPolygonOutline: Boolean = true,
    val defaultPointRadius: Float = 6f,
    val defaultPointFillColorHex: String = GeoVaultColorTokens.Hex.MapPointDefault,
    val defaultPointStrokeColorHex: String = GeoVaultColorTokens.Hex.Surface,
    val defaultPointStrokeWidth: Float = 1.5f,
    val defaultLabelTextColorHex: String = GeoVaultColorTokens.Hex.MapLabelText,
    val defaultLabelTextSize: Float = 12f,
    /**
     * Vertical component of [org.maplibre.android.style.layers.PropertyFactory.textOffset] (in em)
     * for point name labels when [showPointTextLabels] is true. Positive values offset text
     * downward from the top anchor; smaller values sit names closer to the geometry.
     */
    val pointLabelTextOffsetYEm: Float = 0.72f,
    /**
     * Text halo width in pixels for point name labels. When `<= 0f`, no halo is applied.
     * [GeoJsonRenderPlugin.applyPointLabelHalo] can still override at runtime.
     */
    val pointLabelHaloWidth: Float = 0f,
    /** ARGB text halo color; if null and [pointLabelHaloWidth] is positive, the plugin uses MapLineworkHalo. */
    val pointLabelHaloColorArgb: Int? = null,
    val defaultIconSize: Float = 1f,
    val defaultIconAnchor: String = Property.ICON_ANCHOR_CENTER,
    val defaultPolygonFillOpacity: Float = 0.35f,
    val defaultPolygonOutlineWidth: Float = 2f,
)

data class GeoJsonPointClusteringConfig(
    val maxZoom: Int = 14,
    val radius: Int = 50,
    val minPoints: Int = 2,
    val circleStyles: List<GeoJsonPointClusterCircleStyle> = defaultCircleStyles(),
    val countTextColorHex: String = GeoVaultColorTokens.Hex.Surface,
    val countTextSize: Float = 12f,
) {
    init {
        require(maxZoom >= 0) { "Cluster maxZoom must be non-negative." }
        require(radius > 0) { "Cluster radius must be positive." }
        require(minPoints >= 2) { "Cluster minPoints must be at least 2." }
        require(circleStyles.isNotEmpty()) { "At least one cluster circle style is required." }
        require(circleStyles.map { it.minPointCount }.distinct().size == circleStyles.size) {
            "Cluster circle style thresholds must be unique."
        }
    }

    val orderedCircleStyles: List<GeoJsonPointClusterCircleStyle> =
        circleStyles.sortedByDescending { it.minPointCount }

    companion object {
        fun defaultCircleStyles(): List<GeoJsonPointClusterCircleStyle> = listOf(
            GeoJsonPointClusterCircleStyle(
                minPointCount = 150,
                circleColorHex = GeoVaultColorTokens.Hex.MainBlue,
                circleRadius = 22f,
            ),
            GeoJsonPointClusterCircleStyle(
                minPointCount = 20,
                circleColorHex = GeoVaultColorTokens.Hex.MainBlue,
                circleRadius = 20f,
            ),
            GeoJsonPointClusterCircleStyle(
                minPointCount = 0,
                circleColorHex = GeoVaultColorTokens.Hex.MainBlue,
                circleRadius = 18f,
            ),
        )
    }
}

data class GeoJsonPointClusterCircleStyle(
    val minPointCount: Int,
    val circleColorHex: String,
    val circleRadius: Float,
) {
    init {
        require(minPointCount >= 0) { "Cluster circle threshold must be non-negative." }
        require(circleColorHex.isNotBlank()) { "Cluster circle color must not be blank." }
        require(circleRadius > 0f) { "Cluster circle radius must be positive." }
    }
}

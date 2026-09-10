package com.geovault.common.maps.render

/**
 * Visual settings for the independent selected-point source owned by [GeoJsonRenderPlugin].
 *
 * This is not part of [MapRenderState]. The main collection stays the durable geometry world;
 * selection is a 0–1 point overlay resolved from a selected feature id.
 */
data class GeoJsonSelectionOverlayConfig(
    val selectedIconImageId: String = CommonMapIconIds.MARKER_SELECTED,
    val selectedIconSize: Float = 1.08f,
    val showPointTextLabels: Boolean = false,
)

/**
 * Resolves the styled overlay point for a selected id against the current render-state points.
 */
internal object GeoJsonSelectionOverlay {
    fun styledPoint(
        selectedId: String?,
        sourcePoints: List<MapRenderPoint>,
        config: GeoJsonSelectionOverlayConfig,
        style: ((MapRenderPoint) -> MapRenderPoint)?,
    ): MapRenderPoint? {
        val id = selectedId ?: return null
        val source = sourcePoints.firstOrNull { it.id == id } ?: return null
        return style?.invoke(source) ?: defaultStyle(source, config)
    }

    fun defaultStyle(
        source: MapRenderPoint,
        config: GeoJsonSelectionOverlayConfig,
    ): MapRenderPoint {
        return source.copy(
            iconImageId = config.selectedIconImageId,
            iconSize = config.selectedIconSize,
            title = if (config.showPointTextLabels) source.title else null,
        )
    }
}

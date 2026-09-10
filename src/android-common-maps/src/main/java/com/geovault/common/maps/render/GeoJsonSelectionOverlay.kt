package com.geovault.common.maps.render

import org.maplibre.android.style.layers.Property

/**
 * Visual settings for the selected-point marker owned by [GeoJsonRenderPlugin].
 *
 * This is not part of [MapRenderState]. The main collection stays the durable geometry world;
 * selection is a 0–1 marker resolved from a selected feature id.
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

internal data class GeoJsonSelectionOverlayPlacement(
    val translationX: Float,
    val translationY: Float,
    val pivotX: Float,
    val pivotY: Float,
)

/**
 * Places the selected-marker view so its icon anchor sits on the projected lat/lng.
 * Scale and rotation are applied around that pivot by the view itself.
 */
internal object GeoJsonSelectionOverlayLayout {
    fun placement(
        screenX: Float,
        screenY: Float,
        width: Float,
        height: Float,
        iconAnchor: String,
    ): GeoJsonSelectionOverlayPlacement {
        val pivotX = width / 2f
        val pivotY = when (iconAnchor) {
            Property.ICON_ANCHOR_BOTTOM -> height
            Property.ICON_ANCHOR_TOP -> 0f
            else -> height / 2f
        }
        return GeoJsonSelectionOverlayPlacement(
            translationX = screenX - pivotX,
            translationY = screenY - pivotY,
            pivotX = pivotX,
            pivotY = pivotY,
        )
    }
}

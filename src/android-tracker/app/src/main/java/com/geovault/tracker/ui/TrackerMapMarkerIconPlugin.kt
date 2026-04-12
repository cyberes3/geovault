package com.geovault.tracker.ui

import android.content.Context
import android.util.Log
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.MapMarkerUtils
import com.geovault.common.maps.render.MapRenderState
import com.geovault.tracker.R
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.parseHexToColorInt
import com.geovault.tracker.presentation.TrackerMapIconIds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

class TrackerMapMarkerIconPlugin(
    private val context: Context,
) : GeoVaultMapPlugin {
    companion object {
        private const val TAG = "TrackerMapMarkerIconPlugin"
    }

    private var map: MapLibreMap? = null

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapDetached() {
        map = null
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        val defaultColorHex = defaultTrackerColorHex(context)
        ensureIcon(style, TrackerMapIconIds.SELECTED_DEFAULT)
        ensureIcon(style, TrackerMapIconIds.selectedForColor(defaultColorHex))
        ensureIcon(style, TrackerMapIconIds.simpleForColor(defaultColorHex))
    }

    fun ensureIconsForRenderState(renderState: MapRenderState) {
        val style = map?.style ?: return
        renderState.points
            .mapNotNull { it.iconImageId }
            .distinct()
            .forEach { ensureIcon(style, it) }
    }

    fun resolveRenderStateWithFallback(renderState: MapRenderState): MapRenderState {
        val style = map?.style ?: return renderState
        val resolvedPoints = renderState.points.map { point ->
            val iconId = point.iconImageId ?: return@map point
            if (ensureIcon(style, iconId)) {
                point
            } else {
                ensureIcon(style, TrackerMapIconIds.SELECTED_DEFAULT)
                point.copy(iconImageId = TrackerMapIconIds.SELECTED_DEFAULT)
            }
        }
        return renderState.copy(points = resolvedPoints)
    }

    private fun ensureIcon(style: Style, imageId: String): Boolean {
        if (style.getImage(imageId) != null) return true
        val spec = if (imageId == TrackerMapIconIds.SELECTED_DEFAULT) {
            TrackerMapIconIds.IconSpec(
                colorHex = defaultTrackerColorHex(context),
                chevronOnly = false,
            )
        } else {
            TrackerMapIconIds.parseSpec(imageId) ?: return false
        }
        val tint = parseHexToColorInt(spec.colorHex, context)
        val bitmap = if (spec.chevronOnly) {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                context = context,
                backgroundResId = R.drawable.ic_empty_32dp,
                foregroundFillResId = R.drawable.ic_track_direction_arrow_chevron_fill,
                foregroundStrokeResId = R.drawable.ic_track_direction_arrow_chevron_stroke_black,
                tintColor = tint,
            )
        } else {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                context = context,
                backgroundResId = R.drawable.ic_track_direction_arrow_circle,
                foregroundFillResId = R.drawable.ic_track_direction_arrow_chevron_fill,
                foregroundStrokeResId = R.drawable.ic_track_direction_arrow_chevron_stroke,
                tintColor = tint,
            )
        } ?: return false
        return try {
            style.addImage(imageId, bitmap, false)
            style.getImage(imageId) != null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add map icon imageId=$imageId", t)
            false
        }
    }
}

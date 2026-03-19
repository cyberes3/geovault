package com.geovault.tracker.fragments.map

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import com.geovault.common.map.LocationComponentHelper
import com.geovault.common.map.MapMarkerUtils
import com.geovault.tracker.R
import com.geovault.tracker.parseHexToColor
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

internal data class MapStyleIds(
    val trackSourceId: String,
    val trackOuterOutlineLayerId: String,
    val trackOutlineLayerId: String,
    val trackFillLayerId: String,
    val trackPositionSourceId: String,
    val trackPositionAccuracySourceId: String,
    val trackPositionLayerId: String,
    val trackPositionAccuracyLayerId: String,
    val allTracksSourceId: String,
    val allTracksPointsSourceId: String,
    val allTracksOuterOutlineLayerId: String,
    val allTracksOutlineLayerId: String,
    val allTracksFillLayerId: String,
    val allTracksPointsLayerId: String
)

internal object MapStyleSetup {
    private const val TRACK_ACCURACY_ALPHA = 64

    fun configure(
        context: Context,
        map: MapLibreMap,
        style: Style,
        ids: MapStyleIds
    ) {
        // Disable symbol placement fade so chevrons appear immediately.
        style.setTransition(TransitionOptions(0L, 0L, false))

        MapMarkerUtils.getMarkerBitmapWithTintedForeground(
            context,
            R.drawable.ic_track_direction_arrow_circle,
            R.drawable.ic_track_direction_arrow_chevron_fill,
            R.drawable.ic_track_direction_arrow_chevron_stroke,
            parseHexToColor(null, context)
        )?.let { bitmap ->
            style.addImage("track-direction-arrow", bitmap)
        }

        style.addSource(
            GeoJsonSource(
                ids.trackSourceId,
                GeoJsonOptions().apply { this["synchronousUpdate"] = true }
            )
        )
        style.addSource(
            GeoJsonSource(
                ids.trackPositionSourceId,
                GeoJsonOptions().apply { this["synchronousUpdate"] = true }
            )
        )
        style.addSource(
            GeoJsonSource(
                ids.trackPositionAccuracySourceId,
                GeoJsonOptions().apply { this["synchronousUpdate"] = true }
            )
        )

        LocationComponentHelper.activate(
            map = map,
            style = style,
            context = context,
            config = LocationComponentHelper.Config(
                accuracyColor = parseHexToColor(null, context),
                accuracyAlpha = 0.25f,
                backgroundDrawable = R.drawable.ic_track_direction_arrow_circle,
                foregroundDrawable = R.drawable.ic_track_direction_arrow,
                renderMode = RenderMode.COMPASS
            )
        )

        val outerOutlineLayer = LineLayer(ids.trackOuterOutlineLayerId, ids.trackSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineColor(ContextCompat.getColor(context, R.color.white)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        }
        val outlineLayer = LineLayer(ids.trackOutlineLayerId, ids.trackSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineColor(Expression.get("outlineColor")),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        }
        val fillLayer = LineLayer(ids.trackFillLayerId, ids.trackSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineColor(Expression.get("lineColor")),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        }
        val trackerBaseColor = parseHexToColor(null, context)
        val accuracyFillColor = ColorUtils.setAlphaComponent(trackerBaseColor, TRACK_ACCURACY_ALPHA)
        val accuracyLayer = FillLayer(ids.trackPositionAccuracyLayerId, ids.trackPositionAccuracySourceId).apply {
            setProperties(
                PropertyFactory.fillColor(accuracyFillColor)
            )
        }
        val symbolLayer = SymbolLayer(ids.trackPositionLayerId, ids.trackPositionSourceId).apply {
            setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(0.75f),
                PropertyFactory.iconRotate(Expression.get("rotate")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            setIconOpacityTransition(TransitionOptions(0L, 0L))
        }
        style.addLayer(outerOutlineLayer)
        style.addLayer(outlineLayer)
        style.addLayer(fillLayer)
        style.addLayer(accuracyLayer)
        style.addLayer(symbolLayer)

        style.addSource(GeoJsonSource(ids.allTracksSourceId))
        style.addSource(
            GeoJsonSource(
                ids.allTracksPointsSourceId,
                GeoJsonOptions().apply { this["synchronousUpdate"] = true }
            )
        )
        val allTracksOuterOutlineLayer = LineLayer(ids.allTracksOuterOutlineLayerId, ids.allTracksSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineColor(ContextCompat.getColor(context, R.color.white)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.visibility(Property.NONE)
            )
        }
        val allTracksOutlineLayer = LineLayer(ids.allTracksOutlineLayerId, ids.allTracksSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineColor(Expression.get("outlineColor")),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.visibility(Property.NONE)
            )
        }
        val allTracksFillLayer = LineLayer(ids.allTracksFillLayerId, ids.allTracksSourceId).apply {
            setProperties(
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineColor(Expression.get("lineColor")),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.visibility(Property.NONE)
            )
        }
        val allTracksPointsLayer = SymbolLayer(ids.allTracksPointsLayerId, ids.allTracksPointsSourceId).apply {
            setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(0.75f),
                PropertyFactory.iconRotate(Expression.get("rotate")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.visibility(Property.NONE)
            )
            setIconOpacityTransition(TransitionOptions(0L, 0L))
        }
        style.addLayer(allTracksOuterOutlineLayer)
        style.addLayer(allTracksOutlineLayer)
        style.addLayer(allTracksFillLayer)
        style.addLayer(allTracksPointsLayer)
    }
}

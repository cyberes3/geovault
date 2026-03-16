package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.common.map.MapMarkerUtils
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.parseHexToColor
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature

internal object MapPointFeatureHelper {

    /**
     * Adds tracker id/name/coords/lastUpdate/owner/hexColor to a point feature for tap resolution.
     */
    fun addTrackerPropertiesToPointFeature(
        feature: Feature,
        tracker: Tracker,
        lat: Double,
        lon: Double,
        lastUpdateMs: Long?,
        context: Context,
        defaultHexColor: String,
        resolveTrackerIsOwner: (Tracker, String) -> Boolean,
        trackerLastUpdateMs: (Tracker) -> Long?
    ) {
        val ts = lastUpdateMs ?: trackerLastUpdateMs(tracker)
        feature.addStringProperty("trackerId", tracker.id)
        feature.addStringProperty("trackerName", tracker.name)
        feature.addNumberProperty("lat", lat)
        feature.addNumberProperty("lon", lon)
        feature.addNumberProperty("lastUpdateMs", (ts ?: 0L).toDouble())
        feature.addNumberProperty("isOwner", if (resolveTrackerIsOwner(tracker, tracker.id)) 1.0 else 0.0)
        val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultHexColor.let { if (it.startsWith("#")) it else "#$it" }
        feature.addStringProperty("hexColor", hexColor)
    }

    /**
     * Ensures the track-direction-arrow image for [hexColor] is in [style].
     * @param chevronOnly when true (all-track mode), use only the chevron icon without the white circle.
     */
    fun ensureArrowImageInStyle(context: Context, style: Style, hexColor: String, chevronOnly: Boolean = false) {
        val suffix = hexColor.replace("#", "")
        val imageId = if (chevronOnly) "track-direction-arrow-simple-$suffix" else "track-direction-arrow-$suffix"
        if (style.getImage(imageId) != null) return
        val tintColor = parseHexToColor(hexColor, context)
        val bitmap = if (chevronOnly) {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                context,
                R.drawable.ic_empty_32dp,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke_black,
                tintColor
            )
        } else {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                context,
                R.drawable.ic_track_direction_arrow_circle,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke,
                tintColor
            )
        }
        bitmap?.let {
            try {
                style.addImage(imageId, it)
            } catch (_: Exception) { }
        }
    }
}

package com.geovault.tracker.fragments.map

import android.content.Context
import android.view.View
import com.geovault.common.map.LocationComponentHelper
import com.geovault.tracker.R
import com.geovault.tracker.parseHexToColor
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.location.modes.RenderMode

internal data class MyLocationButtonState(
    val visibility: Int,
    val showLoading: Boolean,
    val iconResId: Int,
    val contentDescriptionResId: Int
)

internal object MapStandaloneLocationController {
    fun shouldConsumePendingAutoZoom(
        pendingAutoZoom: Boolean,
        trackerFocusIntentActive: Boolean,
        suppressStandaloneAutoZoom: Boolean,
        zoomApplied: Boolean
    ): Boolean {
        return pendingAutoZoom &&
            !trackerFocusIntentActive &&
            !suppressStandaloneAutoZoom &&
            zoomApplied
    }

    fun myLocationButtonState(
        trackingRunning: Boolean,
        showMyLocationEnabled: Boolean,
        waitingForFix: Boolean,
        context: Context
    ): MyLocationButtonState {
        val visible = !trackingRunning
        if (!visible) {
            return MyLocationButtonState(
                visibility = View.GONE,
                showLoading = false,
                iconResId = R.drawable.ic_location_disabled,
                contentDescriptionResId = R.string.show_my_location_description
            )
        }
        val showLoading = showMyLocationEnabled && waitingForFix
        val (iconResId, contentDescriptionResId) = when {
            showLoading -> R.drawable.ic_location_disabled to R.string.waiting_for_gps_lock
            showMyLocationEnabled -> R.drawable.ic_location_enabled to R.string.show_my_location_on_description
            else -> R.drawable.ic_location_disabled to R.string.show_my_location_description
        }
        return MyLocationButtonState(
            visibility = View.VISIBLE,
            showLoading = showLoading,
            iconResId = iconResId,
            contentDescriptionResId = contentDescriptionResId
        )
    }

    fun applyStandaloneStyle(map: MapLibreMap, context: Context) {
        LocationComponentHelper.applyStyle(
            map,
            context,
            LocationComponentHelper.Config(
                accuracyColor = parseHexToColor(null, context),
                accuracyAlpha = 0.25f,
                backgroundDrawable = R.drawable.ic_my_location_marker,
                foregroundDrawable = R.drawable.ic_my_location_marker,
                renderMode = RenderMode.NORMAL
            )
        )
    }

    fun applyTrackerStyle(map: MapLibreMap, context: Context) {
        LocationComponentHelper.applyStyle(
            map,
            context,
            LocationComponentHelper.Config(
                accuracyColor = parseHexToColor(null, context),
                accuracyAlpha = 0.25f,
                backgroundDrawable = R.drawable.ic_track_direction_arrow_circle,
                foregroundDrawable = R.drawable.ic_track_direction_arrow,
                renderMode = RenderMode.COMPASS
            )
        )
    }
}

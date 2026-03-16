package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.R
import org.maplibre.geojson.Feature
import java.util.Locale

internal object MapSelectionUtils {
    fun selectedFromDisplayedState(
        displayedTrackerId: String,
        displayedTrackerName: String?,
        displayedTrackerIsOwner: Boolean,
        lat: Double,
        lon: Double,
        currentTrackerColor: String?,
        defaultHexColor: String,
        lastStreamedPointTimeMs: Long?,
        lastCachedUpdateTimeMs: Long?,
        displayedTrackerLastUpdateMs: Long?,
        lastKnownUpdateMs: Long?
    ): SelectedMapTracker {
        val hexColor = currentTrackerColor ?: defaultHexColor
        val lastUpdateMs = lastStreamedPointTimeMs
            ?: lastCachedUpdateTimeMs
            ?: displayedTrackerLastUpdateMs
            ?: lastKnownUpdateMs
        return SelectedMapTracker(
            id = displayedTrackerId,
            name = displayedTrackerName ?: "",
            lat = lat,
            lon = lon,
            lastUpdateMs = lastUpdateMs,
            isOwner = displayedTrackerIsOwner,
            hexColor = hexColor
        )
    }

    fun selectedFromFeature(
        feature: Feature,
        defaultHexColor: String,
        lastKnownById: Map<String, Long>,
        resolveTrackerIsOwner: (String) -> Boolean
    ): SelectedMapTracker? {
        if (feature.properties() == null) return null
        val id = featurePropertyString(feature, "trackerId") ?: return null
        val name = featurePropertyString(feature, "trackerName") ?: ""
        val lat = featurePropertyDouble(feature, "lat") ?: return null
        val lon = featurePropertyDouble(feature, "lon") ?: return null
        val lastUpdateMs = featurePropertyDouble(feature, "lastUpdateMs")?.toLong()?.takeIf { it > 0L }
            ?: lastKnownById[id]
        val isOwner = featurePropertyDouble(feature, "isOwner")?.let { it == 1.0 }
            ?: resolveTrackerIsOwner(id)
        val hexColor = featurePropertyString(feature, "hexColor") ?: defaultHexColor
        return SelectedMapTracker(id, name, lat, lon, lastUpdateMs, isOwner, hexColor)
    }

    fun formatCoords(lat: Double, lon: Double): String {
        return "%.4f, %.4f".format(Locale.US, lat, lon)
    }

    fun formatLastUpdated(context: Context, lastUpdateMs: Long?): String {
        if (lastUpdateMs == null) return context.getString(R.string.waiting_for_data)
        val diffMs = System.currentTimeMillis() - lastUpdateMs
        val diffSec = (diffMs / 1000).coerceAtLeast(0)
        val (n, unitResId) = when {
            diffSec < 60 -> {
                val n = diffSec.toInt()
                n to if (n == 1) R.string.map_updated_sec else R.string.map_updated_secs
            }
            diffSec < 3600 -> {
                val n = (diffSec / 60).toInt()
                n to if (n == 1) R.string.map_updated_min else R.string.map_updated_mins
            }
            diffSec < 86400 -> {
                val n = (diffSec / 3600).toInt()
                n to if (n == 1) R.string.map_updated_hr else R.string.map_updated_hrs
            }
            else -> {
                val n = (diffSec / 86400).toInt()
                n to if (n == 1) R.string.map_updated_day_short else R.string.map_updated_days_short
            }
        }
        return context.getString(R.string.map_updated_ago, n, context.getString(unitResId))
    }

    private fun featurePropertyString(feature: Feature, key: String): String? {
        val v = feature.properties()?.get(key) ?: return null
        return v.toString().trim('"')
    }

    private fun featurePropertyDouble(feature: Feature, key: String): Double? {
        val v = feature.properties()?.get(key) ?: return null
        return v.toString().toDoubleOrNull()
    }
}

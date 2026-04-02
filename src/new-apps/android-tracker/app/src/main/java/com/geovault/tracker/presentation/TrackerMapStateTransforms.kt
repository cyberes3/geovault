package com.geovault.tracker.presentation

import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.MapRenderLine
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Local map display mode. [SINGLE_SESSION] vs [ALL_QUEUE] currently share the same on-device queue
 * source; split is reserved for future server/multi-tracker parity.
 */
enum class TrackerMapDisplayMode {
    SINGLE_SESSION,
    ALL_QUEUE,
    GROUP_PLACEHOLDER,
}

object TrackerMapStateTransforms {

    const val TRAIL_LINE_COLOR_HEX: String = "#1E88E5"
    const val TRAIL_OUTLINE_COLOR_HEX: String = "#FFFFFF"

    fun buildRenderState(
        mode: TrackerMapDisplayMode,
        trail: List<QueuedLocation>,
        runtime: TrackingRuntimeSnapshot,
    ): MapRenderState {
        if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return MapRenderState()
        }

        // TODO(chunk-map): For SINGLE_SESSION, filter trail by session boundary when exposed on runtime;
        // ALL_QUEUE will then include historical backlog across sessions.
        val effectiveTrail = trail

        val line = buildTrailLine(effectiveTrail)
        val lastQueued = effectiveTrail.lastOrNull()
        val lastLat = lastQueued?.latitude ?: runtime.lastTrackedLatitude
        val lastLon = lastQueued?.longitude ?: runtime.lastTrackedLongitude
        val lastBearing = lastQueued?.bearing

        val markers = mutableListOf<MapRenderPoint>()
        if (lastLat != null && lastLon != null) {
            markers.add(
                MapRenderPoint(
                    id = "last-fix",
                    latitude = lastLat,
                    longitude = lastLon,
                    title = runtime.selectedTrackerName.takeIf { it.isNotBlank() },
                    iconImageId = CommonMapIconIds.MARKER_DEFAULT,
                    iconRotationDegrees = lastBearing,
                    iconSize = 1.05f,
                ),
            )
        }

        return MapRenderState(
            points = markers,
            lines = listOfNotNull(line),
        )
    }

    fun trailBounds(trail: List<QueuedLocation>): LatLngBounds? {
        if (trail.isEmpty()) return null
        val latLngs = trail.map { LatLng(it.latitude, it.longitude) }
        if (latLngs.size == 1) {
            val p = latLngs.first()
            return LatLngBounds.from(p.latitude, p.longitude, p.latitude, p.longitude)
        }
        val b = LatLngBounds.Builder()
        latLngs.forEach { b.include(it) }
        return b.build()
    }

    private fun buildTrailLine(trail: List<QueuedLocation>): MapRenderLine? {
        if (trail.size < 2) return null
        val pairs = trail.map { Pair(it.latitude, it.longitude) }
        return MapRenderLine(
            id = "tracker-trail",
            coordinates = pairs,
            lineColorHex = TRAIL_LINE_COLOR_HEX,
            outlineColorHex = TRAIL_OUTLINE_COLOR_HEX,
        )
    }
}

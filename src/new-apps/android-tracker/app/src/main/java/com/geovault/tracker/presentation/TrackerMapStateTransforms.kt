package com.geovault.tracker.presentation

import com.geovault.common.maps.core.geoVaultSplitTrackByDistance
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.MapRenderLine
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
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
    const val DEFAULT_MULTI_TRACK_LINE_COLOR_HEX: String = "#607D8B"
    const val MAX_TRACK_JUMP_METERS: Float = 5f * 1609.344f

    fun buildRenderState(
        mode: TrackerMapDisplayMode,
        trail: List<QueuedLocation>,
        runtime: TrackingRuntimeSnapshot,
        remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
        activeStreamedTrackerIds: Set<String> = emptySet(),
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
        trackerColorById: Map<String, String> = emptyMap(),
    ): MapRenderState {
        val effectiveTrail = effectiveTrail(mode, trail, runtime)
        val lines = buildTrailLines(
            mode = mode,
            effectiveTrail = effectiveTrail,
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            trackerColorById = trackerColorById
        )
        val lastQueued = effectiveTrail.lastOrNull()
        val isMultiContext = mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        val lastLat = if (isMultiContext) null else (runtime.lastTrackedLatitude ?: lastQueued?.latitude)
        val lastLon = if (isMultiContext) null else (runtime.lastTrackedLongitude ?: lastQueued?.longitude)
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
        remoteLastPoints.values
            .filter { activeStreamedTrackerIds.isNotEmpty() && it.trackId in activeStreamedTrackerIds }
            .forEach { point ->
                markers.add(
                    MapRenderPoint(
                        id = "remote-${point.trackId}",
                        latitude = point.lat,
                        longitude = point.lon,
                        title = point.trackId,
                        iconImageId = CommonMapIconIds.MARKER_DEFAULT,
                        iconSize = 0.9f,
                    )
                )
            }

        return MapRenderState(
            points = markers,
            lines = lines,
        )
    }

    fun effectiveTrail(
        mode: TrackerMapDisplayMode,
        trail: List<QueuedLocation>,
        runtime: TrackingRuntimeSnapshot
    ): List<QueuedLocation> {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return trail
        val boundaryId = runtime.sessionVisibleBoundaryId
        if (boundaryId <= 0L) return trail
        // Live in-memory overlay points use non-positive ids; keep them in session mode.
        return trail.filter { it.id <= 0L || it.id > boundaryId }
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

    fun multiTrailBounds(trailsByTracker: Map<String, List<QueuedLocation>>): LatLngBounds? {
        if (trailsByTracker.isEmpty()) return null
        val allPoints = trailsByTracker.values.flatten()
        return trailBounds(allPoints)
    }

    private fun buildTrailLines(
        mode: TrackerMapDisplayMode,
        effectiveTrail: List<QueuedLocation>,
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>
    ): List<MapRenderLine> {
        return if (
            (mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) &&
            allQueueTrailsByTracker.isNotEmpty()
        ) {
            buildAllQueueLines(allQueueTrailsByTracker, trackerColorById)
        } else {
            buildSegmentedLines(
                lineIdPrefix = "tracker-trail",
                points = effectiveTrail.map { it.latitude to it.longitude },
                lineColorHex = TRAIL_LINE_COLOR_HEX
            )
        }
    }

    private fun buildAllQueueLines(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>
    ): List<MapRenderLine> {
        return allQueueTrailsByTracker.entries
            .sortedBy { it.key }
            .flatMap { (trackerId, queuedLocations) ->
                val color = normalizeColor(trackerColorById[trackerId]) ?: DEFAULT_MULTI_TRACK_LINE_COLOR_HEX
                buildSegmentedLines(
                    lineIdPrefix = "all-track-$trackerId",
                    points = queuedLocations.map { it.latitude to it.longitude },
                    lineColorHex = color
                )
            }
    }

    private fun buildSegmentedLines(
        lineIdPrefix: String,
        points: List<Pair<Double, Double>>,
        lineColorHex: String
    ): List<MapRenderLine> {
        val segments = geoVaultSplitTrackByDistance(points, MAX_TRACK_JUMP_METERS)
        return segments.mapIndexed { index, segment ->
            MapRenderLine(
                id = "$lineIdPrefix-$index",
                coordinates = segment,
                lineColorHex = lineColorHex,
                outlineColorHex = TRAIL_OUTLINE_COLOR_HEX
            )
        }
    }

    private fun normalizeColor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("#")) raw else "#$raw"
    }
}

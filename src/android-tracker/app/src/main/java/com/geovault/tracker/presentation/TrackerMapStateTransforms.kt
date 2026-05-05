package com.geovault.tracker.presentation

import com.geovault.common.maps.core.geoVaultSplitTrackByDistance
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.render.MapRenderLine
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.common.ui.theme.GeoVaultColorTokens
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

    const val MAX_TRACK_JUMP_METERS: Float = 5f * 1609.344f
    private val accuracyCircleResolver = TrackerAccuracyCircleResolver()

    fun buildRenderState(
        session: TrackerMapSessionSnapshot,
        cosmetics: TrackerMapRenderCosmetics,
        accuracy: TrackerMapAccuracyRenderModel = TrackerMapAccuracyRenderModel(),
    ): MapRenderState {
        return buildRenderState(
            mode = session.mode,
            trail = session.singleTrail,
            runtime = session.runtime,
            remoteLastPoints = session.acceptedRemoteLastPoints,
            activeStreamedTrackerIds = session.uiState.activeStreamedTrackerIds,
            streamTargetIds = session.uiState.streamTargetIds,
            acceptedRemoteTrackerIds = session.plan.acceptedRemoteTrackerIds,
            allQueueTrailsByTracker = session.renderTrailsByTracker,
            trackerColorById = cosmetics.trackerColorById,
            trackerDisplayNameById = cosmetics.trackerDisplayNameById,
            displayedTrackerId = session.plan.displayedTrackerId,
            selectedMapTrackerId = cosmetics.selectedMapTrackerId,
            trackerRenderOrder = cosmetics.trackerRenderOrder,
            streamedAccuracyMeters = accuracy.streamedAccuracyMeters,
            fallbackAccuracyMeters = accuracy.fallbackAccuracyMeters,
            allowAccuracyFallback = accuracy.allowAccuracyFallback,
            streamedAccuracyByTrackerId = accuracy.streamedAccuracyByTrackerId,
            fallbackAccuracyByTrackerId = accuracy.fallbackAccuracyByTrackerId,
            allowAccuracyFallbackByTrackerId = accuracy.allowAccuracyFallbackByTrackerId,
            defaultIconColorHex = cosmetics.defaultIconColorHex,
            displayedTrackerName = session.plan.displayedTrackerName,
        )
    }

    fun buildRenderState(
        mode: TrackerMapDisplayMode,
        trail: List<QueuedLocation>,
        runtime: TrackingRuntimeSnapshot,
        remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
        activeStreamedTrackerIds: Set<String> = emptySet(),
        streamTargetIds: Set<String> = emptySet(),
        acceptedRemoteTrackerIds: Set<String> = TrackerMapRemoteAcceptancePolicy.mergedAcceptedRemoteTrackerIds(
            streamTargetIds = streamTargetIds,
            activeStreamedTrackerIds = activeStreamedTrackerIds,
        ),
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
        trackerColorById: Map<String, String> = emptyMap(),
        trackerDisplayNameById: Map<String, String> = emptyMap(),
        displayedTrackerId: String = "",
        selectedMapTrackerId: String? = null,
        trackerRenderOrder: List<String> = emptyList(),
        streamedAccuracyMeters: Float? = null,
        fallbackAccuracyMeters: Float? = null,
        allowAccuracyFallback: Boolean = false,
        streamedAccuracyByTrackerId: Map<String, Float> = emptyMap(),
        fallbackAccuracyByTrackerId: Map<String, Float> = emptyMap(),
        allowAccuracyFallbackByTrackerId: Set<String> = emptySet(),
        defaultIconColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
        displayedTrackerName: String = "",
    ): MapRenderState {
        val singleIconId = TrackerMapMarkerStylePolicy.singleTrackerIconId(
            trackerColorById = trackerColorById,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = runtime.selectedTrackerId,
            fallbackColorHex = defaultIconColorHex,
        )
        val singleLineColorHex = TrackerMapIconIds.parseSpec(singleIconId)?.colorHex
            ?: TrackerMapIconIds.DEFAULT_COLOR_HEX
        val renderTrail = trail
        val lines = buildTrailLines(
            mode = mode,
            effectiveTrail = renderTrail,
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            trackerColorById = trackerColorById,
            singleTrackerLineColorHex = singleLineColorHex,
        )
        val markers = mutableListOf<MapRenderPoint>()
        if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            val lastQueued = renderTrail.lastOrNull()
            val effectiveDisplayedTrackerId = displayedTrackerId.trim().ifEmpty { runtime.selectedTrackerId.trim() }
            val runtimePointVisible = effectiveDisplayedTrackerId.isNotEmpty() &&
                effectiveDisplayedTrackerId == runtime.selectedTrackerId.trim() &&
                runtime.lastTrackedLatitude != null &&
                runtime.lastTrackedLongitude != null
            val lastLat = lastQueued?.latitude ?: runtime.lastTrackedLatitude.takeIf { runtimePointVisible }
            val lastLon = lastQueued?.longitude ?: runtime.lastTrackedLongitude.takeIf { runtimePointVisible }
            val lastRotation = trackDirectionDegrees(validLatLngsFromTrail(renderTrail))
            if (lastLat != null && lastLon != null && isValidMapLibreGeographicLatLng(lastLat, lastLon)) {
                markers.add(
                    MapRenderPoint(
                        id = "last-fix",
                        latitude = lastLat,
                        longitude = lastLon,
                        title = singleMarkerTitle(displayedTrackerId, displayedTrackerName, runtime, trackerDisplayNameById),
                        iconImageId = singleIconId,
                        iconRotationDegrees = lastRotation,
                    )
                )
            }
        } else {
            val selectedMarkerTrackerId = selectedMapTrackerId?.trim().orEmpty()
            val orderedTrackerIds = buildList {
                trackerRenderOrder.map { it.trim() }.filter { it.isNotEmpty() }.forEach { id ->
                    if (id in allQueueTrailsByTracker) add(id)
                }
                allQueueTrailsByTracker.keys.sorted().forEach { id ->
                    if (id !in this) add(id)
                }
            }
            orderedTrackerIds.forEach { trackerId ->
                val trackerTrail = allQueueTrailsByTracker[trackerId] ?: return@forEach
                    val lastPoint = trackerTrail.lastOrNull() ?: return@forEach
                    if (!isValidMapLibreGeographicLatLng(lastPoint.latitude, lastPoint.longitude)) return@forEach
                    val rotation = trackDirectionDegrees(validLatLngsFromTrail(trackerTrail))
                    val iconId = TrackerMapMarkerStylePolicy.multiTrackerIconId(
                        trackerId = trackerId,
                        trackerColorById = trackerColorById,
                        selectedMapTrackerId = selectedMarkerTrackerId,
                        fallbackColorHex = defaultIconColorHex,
                    )
                    markers.add(
                        MapRenderPoint(
                            id = "remote-$trackerId",
                            latitude = lastPoint.latitude,
                            longitude = lastPoint.longitude,
                            title = trackerDisplayNameById[trackerId]?.trim()?.takeIf { it.isNotEmpty() } ?: trackerId,
                            iconImageId = iconId,
                            iconRotationDegrees = rotation,
                        )
                    )
            }
            val renderedTrackerIds = markers.map { it.id.removePrefix("remote-") }.toSet()
            val remoteMarkerTrackerIds = acceptedRemoteTrackerIds
            remoteLastPoints.values
                .filter { remoteMarkerTrackerIds.isNotEmpty() && it.trackId in remoteMarkerTrackerIds }
                .filter { it.trackId !in renderedTrackerIds }
                .forEach { point ->
                    if (!isValidMapLibreGeographicLatLng(point.lat, point.lon)) return@forEach
                    val iconId = TrackerMapMarkerStylePolicy.multiTrackerIconId(
                        trackerId = point.trackId,
                        trackerColorById = trackerColorById,
                        selectedMapTrackerId = selectedMarkerTrackerId,
                        fallbackColorHex = defaultIconColorHex,
                    )
                    markers.add(
                        MapRenderPoint(
                            id = "remote-${point.trackId}",
                            latitude = point.lat,
                            longitude = point.lon,
                            title = trackerDisplayNameById[point.trackId]?.trim()?.takeIf { it.isNotEmpty() }
                                ?: point.trackId,
                            iconImageId = iconId,
                        )
                    )
                }
        }

        val normalizedStreamedAccuracyByTracker = streamedAccuracyByTrackerId.mapKeys { it.key.trim() }
        val normalizedFallbackAccuracyByTracker = fallbackAccuracyByTrackerId.mapKeys { it.key.trim() }
        val normalizedAllowFallbackTrackerIds = allowAccuracyFallbackByTrackerId
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val defaultSingleTrackerId = displayedTrackerId.trim().ifEmpty { runtime.selectedTrackerId.trim() }
        val polygons = buildAccuracyPolygons(
            markers = markers,
            markerColorById = buildMarkerColorById(
                mode = mode,
                markers = markers,
                trackerColorById = trackerColorById,
                singleLineColorHex = singleLineColorHex,
            ),
            streamedAccuracyMeters = streamedAccuracyMeters,
            fallbackAccuracyMeters = fallbackAccuracyMeters,
            allowAccuracyFallback = allowAccuracyFallback,
            streamedAccuracyByTrackerId = normalizedStreamedAccuracyByTracker,
            fallbackAccuracyByTrackerId = normalizedFallbackAccuracyByTracker,
            allowAccuracyFallbackByTrackerId = normalizedAllowFallbackTrackerIds,
            defaultSingleTrackerId = defaultSingleTrackerId,
        )

        return MapRenderState(
            points = markers,
            lines = lines,
            polygons = polygons,
        )
    }

    fun trailBounds(trail: List<QueuedLocation>): LatLngBounds? {
        val valid = trail.filter { isValidMapLibreGeographicLatLng(it.latitude, it.longitude) }
        if (valid.isEmpty()) return null
        val latLngs = valid.map { LatLng(it.latitude, it.longitude) }
        if (latLngs.size == 1) {
            val p = latLngs.first()
            return LatLngBounds.from(p.latitude, p.longitude, p.latitude, p.longitude)
        }
        val b = LatLngBounds.Builder()
        latLngs.forEach { b.include(it) }
        return b.build()
    }

    private fun validLatLngsFromTrail(trail: List<QueuedLocation>): List<LatLng> {
        return trail.mapNotNull { q ->
            if (isValidMapLibreGeographicLatLng(q.latitude, q.longitude)) {
                LatLng(q.latitude, q.longitude)
            } else {
                null
            }
        }
    }

    fun multiTrailBounds(trailsByTracker: Map<String, List<QueuedLocation>>): LatLngBounds? {
        if (trailsByTracker.isEmpty()) return null
        val allPoints = trailsByTracker.values.flatten()
        return trailBounds(allPoints)
    }

    fun remoteLastPointBounds(remoteLastPoints: Map<String, TrackPointEvent>): LatLngBounds? {
        val valid = remoteLastPoints.values
            .filter { isValidMapLibreGeographicLatLng(it.lat, it.lon) }
            .map { LatLng(it.lat, it.lon) }
        if (valid.isEmpty()) return null
        if (valid.size == 1) {
            val point = valid.first()
            return LatLngBounds.from(point.latitude, point.longitude, point.latitude, point.longitude)
        }
        val bounds = LatLngBounds.Builder()
        valid.forEach { bounds.include(it) }
        return bounds.build()
    }

    fun mergeBounds(first: LatLngBounds?, second: LatLngBounds?): LatLngBounds? {
        if (first == null) return second
        if (second == null) return first
        val bounds = LatLngBounds.Builder()
        bounds.include(first.northEast)
        bounds.include(first.southWest)
        bounds.include(second.northEast)
        bounds.include(second.southWest)
        return bounds.build()
    }

    private fun buildTrailLines(
        mode: TrackerMapDisplayMode,
        effectiveTrail: List<QueuedLocation>,
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>,
        singleTrackerLineColorHex: String,
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
                lineColorHex = singleTrackerLineColorHex,
            )
        }
    }

    private fun buildAllQueueLines(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>,
    ): List<MapRenderLine> {
        return allQueueTrailsByTracker.entries
            .sortedBy { it.key }
            .flatMap { (trackerId, queuedLocations) ->
                val color = normalizeColor(trackerColorById[trackerId]) ?: GeoVaultColorTokens.Hex.Gray500
                buildSegmentedLines(
                    lineIdPrefix = "all-track-$trackerId",
                    points = queuedLocations.map { it.latitude to it.longitude },
                    lineColorHex = color,
                )
            }
    }

    private fun buildSegmentedLines(
        lineIdPrefix: String,
        points: List<Pair<Double, Double>>,
        lineColorHex: String,
    ): List<MapRenderLine> {
        val segments = geoVaultSplitTrackByDistance(points, MAX_TRACK_JUMP_METERS)
        return segments.mapIndexed { index, segment ->
            MapRenderLine(
                id = "$lineIdPrefix-$index",
                coordinates = segment,
                lineColorHex = lineColorHex,
            )
        }
    }

    private fun normalizeColor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("#")) raw else "#$raw"
    }

    private fun trackDirectionDegrees(points: List<LatLng>): Float {
        val validPoints = points.filter(::isValidPoint)
        if (validPoints.size < 2) return 0f
        val last = validPoints.last()
        for (i in validPoints.size - 2 downTo 0) {
            val prev = validPoints[i]
            val dLon = last.longitude - prev.longitude
            val dLat = last.latitude - prev.latitude
            if (dLon != 0.0 || dLat != 0.0) {
                return (Math.atan2(dLon, dLat) * 180.0 / Math.PI).toFloat()
            }
        }
        return 0f
    }

    private fun isValidPoint(point: LatLng): Boolean {
        return point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0
    }

    private fun singleMarkerTitle(
        displayedTrackerId: String,
        displayedTrackerName: String,
        runtime: TrackingRuntimeSnapshot,
        trackerDisplayNameById: Map<String, String>,
    ): String? {
        val displayedId = displayedTrackerId.trim()
        return trackerDisplayNameById[displayedId]?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayedTrackerName.trim().takeIf { it.isNotEmpty() }
            ?: runtime.selectedTrackerName.takeIf {
                displayedId.isEmpty() || displayedId == runtime.selectedTrackerId.trim()
            }?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayedId.takeIf { it.isNotEmpty() }
    }

    private fun buildAccuracyPolygons(
        markers: List<MapRenderPoint>,
        markerColorById: Map<String, String>,
        streamedAccuracyMeters: Float?,
        fallbackAccuracyMeters: Float?,
        allowAccuracyFallback: Boolean,
        streamedAccuracyByTrackerId: Map<String, Float>,
        fallbackAccuracyByTrackerId: Map<String, Float>,
        allowAccuracyFallbackByTrackerId: Set<String>,
        defaultSingleTrackerId: String,
    ): List<com.geovault.common.maps.render.MapRenderPolygon> {
        val accuracyInputs = markers.map { marker ->
            val trackerId = trackerIdForMarker(marker.id, defaultSingleTrackerId)
            val streamed = streamedAccuracyByTrackerId[trackerId] ?: streamedAccuracyMeters
            val fallback = fallbackAccuracyByTrackerId[trackerId] ?: fallbackAccuracyMeters
            val allowFallback = trackerId in allowAccuracyFallbackByTrackerId || allowAccuracyFallback
            TrackerAccuracyCircleInput(
                polygonId = polygonIdForMarker(marker.id, trackerId),
                trackerId = trackerId,
                centerLatitude = marker.latitude,
                centerLongitude = marker.longitude,
                streamedAccuracyMeters = streamed,
                fallbackAccuracyMeters = fallback,
                allowFallback = allowFallback,
                colorHex = markerColorById[marker.id] ?: TrackerMapIconIds.DEFAULT_COLOR_HEX,
            )
        }
        return accuracyCircleResolver.buildPolygons(accuracyInputs)
    }

    private fun buildMarkerColorById(
        mode: TrackerMapDisplayMode,
        markers: List<MapRenderPoint>,
        trackerColorById: Map<String, String>,
        singleLineColorHex: String,
    ): Map<String, String> {
        return markers.associate { marker ->
            val color = when (mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> singleLineColorHex
                TrackerMapDisplayMode.ALL_QUEUE,
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                    val trackerId = marker.id.removePrefix("remote-").trim()
                    normalizeColor(trackerColorById[trackerId]) ?: GeoVaultColorTokens.Hex.Gray500
                }
            }
            marker.id to color
        }
    }

    private fun trackerIdForMarker(markerId: String, defaultSingleTrackerId: String): String {
        return if (markerId == "last-fix") {
            defaultSingleTrackerId
        } else {
            markerId.removePrefix("remote-").trim()
        }
    }

    private fun polygonIdForMarker(markerId: String, trackerId: String): String {
        return if (markerId == "last-fix") {
            "accuracy-last-fix"
        } else {
            "accuracy-${trackerId.ifEmpty { markerId }}"
        }
    }
}

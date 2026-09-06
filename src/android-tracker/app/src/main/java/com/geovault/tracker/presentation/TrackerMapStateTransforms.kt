package com.geovault.tracker.presentation

import com.geovault.common.geo.Wgs84Point
import com.geovault.common.maps.core.geoVaultSplitTrackByDistance
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.render.MapRenderLine
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.common.ui.theme.GeoVaultColorHex
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.StreamingTargetPolicy
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
    /** Break line segments when fixes are separated by a long gap (history / not recording). */
    const val MAX_TRACK_TIME_GAP_MS: Long = 5L * 60L * 1_000L
    /**
     * While locally recording, the filter may hold fixes for several minutes without persisting
     * a trail point; use a wider gap so the live trail does not fragment into orphan segments.
     */
    const val MAX_TRACK_TIME_GAP_WHILE_RECORDING_MS: Long = 15L * 60L * 1_000L
    private const val LIVE_DRAW_TRAIL_POINT_LIMIT: Int = 4000
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
            fallbackAccuracyMeters = accuracy.fallbackAccuracyMeters,
            allowAccuracyFallback = accuracy.allowAccuracyFallback,
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
        // DEAD-CODE REMOVAL: previously routed through
        // `TrackerMapRemoteAcceptancePolicy.mergedAcceptedRemoteTrackerIds`, whose result
        // (`projectedIds + (activeIds intersect projectedIds)`) always equals `projectedIds` —
        // `activeStreamedTrackerIds` never actually changed the outcome.
        acceptedRemoteTrackerIds: Set<String> = StreamingTargetPolicy.normalizeTrackerIds(streamTargetIds),
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
        trackerColorById: Map<String, String> = emptyMap(),
        trackerDisplayNameById: Map<String, String> = emptyMap(),
        displayedTrackerId: String = "",
        selectedMapTrackerId: String? = null,
        trackerRenderOrder: List<String> = emptyList(),
        fallbackAccuracyMeters: Float? = null,
        allowAccuracyFallback: Boolean = false,
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
        val effectiveDisplayedTrackerId = displayedTrackerId.trim().ifEmpty { runtime.selectedTrackerId.trim() }
        val renderTrail = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            TrackerMapLiveDrawMerge.mergeSingle(
                mappedTrail = trail,
                unpublishedOverlay = emptyList(),
                remoteLastPoint = remoteLastPoints[effectiveDisplayedTrackerId],
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trailPointLimit = LIVE_DRAW_TRAIL_POINT_LIMIT,
            )
        } else {
            trail
        }
        val renderMultiTrails = if (
            mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        ) {
            TrackerMapLiveDrawMerge.mergeMulti(
                mappedTrails = allQueueTrailsByTracker,
                unpublishedOverlaysByTracker = emptyMap(),
                remoteLastPoints = remoteLastPoints.filterKeys { it in acceptedRemoteTrackerIds },
                runtime = runtime,
                mode = mode,
                groupTrackerIds = allQueueTrailsByTracker.keys + acceptedRemoteTrackerIds,
                trailPointLimit = LIVE_DRAW_TRAIL_POINT_LIMIT,
            )
        } else {
            allQueueTrailsByTracker
        }
        val resolveState = TrackerMapUiState(
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            runtime = runtime,
            trail = renderTrail,
            allQueueTrailsByTracker = renderMultiTrails,
            remoteLastPoints = remoteLastPoints,
        )
        val lines = buildTrailLines(
            mode = mode,
            effectiveTrail = renderTrail,
            allQueueTrailsByTracker = renderMultiTrails,
            trackerColorById = trackerColorById,
            singleTrackerLineColorHex = singleLineColorHex,
            runtime = runtime,
        )
        val markerFeatures = mutableListOf<TrackerMarkerRenderFeature>()
        if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            val resolved = TrackerMapLastPointResolver.resolve(
                state = resolveState,
                trackerId = effectiveDisplayedTrackerId,
                tracker = null,
                acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            )
            val lastLat = resolved?.latitude
            val lastLon = resolved?.longitude
            val lastAccuracy = resolved?.accuracyMeters
            val lastRotation = if (lastLat != null && lastLon != null) {
                markerDirectionDegrees(renderTrail, lastLat, lastLon)
            } else {
                0f
            }
            if (lastLat != null && lastLon != null && isValidMapLibreGeographicLatLng(lastLat, lastLon)) {
                markerFeatures.add(
                    TrackerMarkerRenderFeature(
                        marker = MapRenderPoint(
                            id = "last-fix",
                            latitude = lastLat,
                            longitude = lastLon,
                            title = singleMarkerTitle(displayedTrackerId, displayedTrackerName, runtime, trackerDisplayNameById),
                            iconImageId = singleIconId,
                            iconRotationDegrees = lastRotation,
                        ),
                        sourceAccuracyMeters = lastAccuracy,
                    )
                )
            }
        } else {
            val selectedMarkerTrackerId = selectedMapTrackerId?.trim().orEmpty()
            val orderedTrackerIds = buildList {
                trackerRenderOrder.map { it.trim() }.filter { it.isNotEmpty() }.forEach { id ->
                    if (id in renderMultiTrails || id in acceptedRemoteTrackerIds) add(id)
                }
                renderMultiTrails.keys.sorted().forEach { id ->
                    if (id !in this) add(id)
                }
                acceptedRemoteTrackerIds.sorted().forEach { id ->
                    if (id !in this) add(id)
                }
            }
            orderedTrackerIds.forEach { trackerId ->
                val trackerTrail = renderMultiTrails[trackerId].orEmpty()
                val resolved = TrackerMapLastPointResolver.resolve(
                    state = resolveState,
                    trackerId = trackerId,
                    tracker = null,
                    acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
                ) ?: return@forEach
                if (!isValidMapLibreGeographicLatLng(resolved.latitude, resolved.longitude)) return@forEach
                val rotation = markerDirectionDegrees(trackerTrail, resolved.latitude, resolved.longitude)
                val iconId = TrackerMapMarkerStylePolicy.multiTrackerIconId(
                    trackerId = trackerId,
                    trackerColorById = trackerColorById,
                    selectedMapTrackerId = selectedMarkerTrackerId,
                    fallbackColorHex = defaultIconColorHex,
                )
                markerFeatures.add(
                    TrackerMarkerRenderFeature(
                        marker = MapRenderPoint(
                            id = "remote-$trackerId",
                            latitude = resolved.latitude,
                            longitude = resolved.longitude,
                            title = trackerDisplayNameById[trackerId]?.trim()?.takeIf { it.isNotEmpty() } ?: trackerId,
                            iconImageId = iconId,
                            iconRotationDegrees = rotation,
                        ),
                        sourceAccuracyMeters = resolved.accuracyMeters,
                    )
                )
            }
        }

        val markers = markerFeatures.map { it.marker }
        val sourceAccuracyByMarkerId = markerFeatures
            .mapNotNull { feature ->
                feature.sourceAccuracyMeters
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?.let { feature.marker.id to it }
            }
            .toMap()
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
            fallbackAccuracyMeters = fallbackAccuracyMeters,
            allowAccuracyFallback = allowAccuracyFallback,
            sourceAccuracyByMarkerId = sourceAccuracyByMarkerId,
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

    private fun markerDirectionDegrees(
        trail: List<QueuedLocation>,
        headLatitude: Double,
        headLongitude: Double,
    ): Float {
        val points = validLatLngsFromTrail(trail).toMutableList()
        val head = LatLng(headLatitude, headLongitude)
        val last = points.lastOrNull()
        if (last == null || last.latitude != head.latitude || last.longitude != head.longitude) {
            points.add(head)
        }
        return trackDirectionDegrees(points)
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
        runtime: TrackingRuntimeSnapshot,
    ): List<MapRenderLine> {
        val maxTimeGapMs = maxTimeGapMsForRuntime(runtime)
        return if (
            (mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) &&
            allQueueTrailsByTracker.isNotEmpty()
        ) {
            buildAllQueueLines(allQueueTrailsByTracker, trackerColorById, maxTimeGapMs)
        } else {
            buildSegmentedLines(
                lineIdPrefix = "tracker-trail",
                points = effectiveTrail,
                lineColorHex = singleTrackerLineColorHex,
                maxTimeGapMs = maxTimeGapMs,
            )
        }
    }

    internal fun maxTimeGapMsForRuntime(runtime: TrackingRuntimeSnapshot): Long {
        return if (runtime.localRecordingActive) {
            MAX_TRACK_TIME_GAP_WHILE_RECORDING_MS
        } else {
            MAX_TRACK_TIME_GAP_MS
        }
    }

    private fun buildAllQueueLines(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>,
        maxTimeGapMs: Long,
    ): List<MapRenderLine> {
        return allQueueTrailsByTracker.entries
            .sortedBy { it.key }
            .flatMap { (trackerId, queuedLocations) ->
                val color = normalizeColor(trackerColorById[trackerId]) ?: GeoVaultColorTokens.Hex.Gray500
                buildSegmentedLines(
                    lineIdPrefix = "all-track-$trackerId",
                    points = queuedLocations,
                    lineColorHex = color,
                    maxTimeGapMs = maxTimeGapMs,
                )
            }
    }

    /**
     * SESSION-AWARE LINE SPLIT: split a tracker's points by recording session
     * (`startTimestampMs`) before applying the existing geographic-distance split. Two
     * adjacent points with different non-null `startTimestampMs` values must never share
     * a line segment — local-queue points from a previous, never-uploaded session can
     * otherwise stitch onto the active session's trail and produce a "spike" that only
     * disappears after a queue-clearing app restart.
     */
    private fun buildSegmentedLines(
        lineIdPrefix: String,
        points: List<QueuedLocation>,
        lineColorHex: String,
        maxTimeGapMs: Long = MAX_TRACK_TIME_GAP_MS,
    ): List<MapRenderLine> {
        if (points.isEmpty()) return emptyList()
        val sessionGroups = groupAdjacentBySession(points)
        val lines = mutableListOf<MapRenderLine>()
        sessionGroups.forEachIndexed { sessionIndex, group ->
            val timeGroups = splitByTimeGap(group, maxTimeGapMs)
            timeGroups.forEachIndexed { timeIndex, timeGroup ->
                val coords = timeGroup.map { Wgs84Point(it.latitude, it.longitude) }
                val distanceSegments = geoVaultSplitTrackByDistance(coords, MAX_TRACK_JUMP_METERS)
                distanceSegments.forEachIndexed { distanceIndex, segment ->
                    lines += MapRenderLine(
                        id = "$lineIdPrefix-$sessionIndex-$timeIndex-$distanceIndex",
                        coordinates = segment.map { it.latitude to it.longitude },
                        lineColorHex = lineColorHex,
                    )
                }
            }
        }
        return lines
    }

    private fun splitByTimeGap(points: List<QueuedLocation>, maxGapMs: Long): List<List<QueuedLocation>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points)
        val groups = mutableListOf<MutableList<QueuedLocation>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val point = points[index]
            val previous = points[index - 1]
            if (point.time - previous.time > maxGapMs) {
                groups += current
                current = mutableListOf()
            }
            current += point
        }
        groups += current
        return groups
    }

    private fun groupAdjacentBySession(points: List<QueuedLocation>): List<List<QueuedLocation>> {
        if (points.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<QueuedLocation>>()
        var currentKey: Long? = points.first().startTimestampMs
        var currentGroup = mutableListOf<QueuedLocation>().apply { add(points.first()) }
        for (i in 1 until points.size) {
            val point = points[i]
            // Adjacent null-start points group together as one session bucket; any
            // change between null and non-null, or between two different non-null values,
            // starts a new group.
            if (point.startTimestampMs == currentKey) {
                currentGroup.add(point)
            } else {
                groups.add(currentGroup)
                currentKey = point.startTimestampMs
                currentGroup = mutableListOf(point)
            }
        }
        groups.add(currentGroup)
        return groups
    }

    private fun normalizeColor(raw: String?): String? = GeoVaultColorHex.normalizeHashPrefix(raw)

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
        fallbackAccuracyMeters: Float?,
        allowAccuracyFallback: Boolean,
        sourceAccuracyByMarkerId: Map<String, Float>,
        fallbackAccuracyByTrackerId: Map<String, Float>,
        allowAccuracyFallbackByTrackerId: Set<String>,
        defaultSingleTrackerId: String,
    ): List<com.geovault.common.maps.render.MapRenderPolygon> {
        val accuracyInputs = markers.map { marker ->
            val trackerId = trackerIdForMarker(marker.id, defaultSingleTrackerId)
            val fallback = fallbackAccuracyByTrackerId[trackerId] ?: fallbackAccuracyMeters
            val allowFallback = trackerId in allowAccuracyFallbackByTrackerId || allowAccuracyFallback
            TrackerAccuracyCircleInput(
                polygonId = polygonIdForMarker(marker.id, trackerId),
                trackerId = trackerId,
                centerLatitude = marker.latitude,
                centerLongitude = marker.longitude,
                sourceAccuracyMeters = sourceAccuracyByMarkerId[marker.id],
                fallbackAccuracyMeters = fallback,
                allowFallback = allowFallback,
                colorHex = markerColorById[marker.id] ?: TrackerMapIconIds.DEFAULT_COLOR_HEX,
            )
        }
        return accuracyCircleResolver.buildPolygons(accuracyInputs)
    }

    private data class TrackerMarkerRenderFeature(
        val marker: MapRenderPoint,
        val sourceAccuracyMeters: Float?,
    )

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

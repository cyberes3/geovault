package com.geovault.tracker.presentation

import com.geovault.common.maps.core.geoVaultSplitTrackByDistance
import com.geovault.common.maps.location.AccuracyGeometryBuilder
import com.geovault.common.maps.location.AccuracyRadiusInput
import com.geovault.common.maps.location.AccuracyRadiusPolicy
import com.geovault.common.maps.location.LatLon
import com.geovault.common.maps.render.MapRenderLine
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderPolygon
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
    const val TRAIL_OUTLINE_COLOR_HEX: String = "#000000"
    const val DEFAULT_MULTI_TRACK_LINE_COLOR_HEX: String = "#607D8B"
    const val MAX_TRACK_JUMP_METERS: Float = 5f * 1609.344f

    fun buildRenderState(
        mode: TrackerMapDisplayMode,
        trail: List<QueuedLocation>,
        runtime: TrackingRuntimeSnapshot,
        trailOutlineColorHex: String = TRAIL_OUTLINE_COLOR_HEX,
        remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
        activeStreamedTrackerIds: Set<String> = emptySet(),
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
        trackerColorById: Map<String, String> = emptyMap(),
        trackerDisplayNameById: Map<String, String> = emptyMap(),
        displayedTrackerId: String = "",
        selectedMapTrackerId: String? = null,
        trackerRenderOrder: List<String> = emptyList(),
        streamedAccuracyMeters: Float? = null,
        fallbackAccuracyMeters: Float? = null,
        allowAccuracyFallback: Boolean = false,
        defaultIconColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): MapRenderState {
        val singleIconId = TrackerMapMarkerStylePolicy.singleTrackerIconId(
            trackerColorById = trackerColorById,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = runtime.selectedTrackerId,
            fallbackColorHex = defaultIconColorHex,
        )
        val singleLineColorHex = TrackerMapIconIds.parseSpec(singleIconId)?.colorHex
            ?: TrackerMapIconIds.DEFAULT_COLOR_HEX
        val effectiveTrail = effectiveTrail(mode, trail, runtime)
        val lines = buildTrailLines(
            mode = mode,
            effectiveTrail = effectiveTrail,
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            trackerColorById = trackerColorById,
            trailOutlineColorHex = trailOutlineColorHex,
            singleTrackerLineColorHex = singleLineColorHex,
        )
        val markers = mutableListOf<MapRenderPoint>()
        if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            val lastQueued = effectiveTrail.lastOrNull()
            val lastLat = lastQueued?.latitude
            val lastLon = lastQueued?.longitude
            val lastRotation = trackDirectionDegrees(
                effectiveTrail.map { LatLng(it.latitude, it.longitude) }
            )
            if (lastLat != null && lastLon != null) {
                markers.add(
                    MapRenderPoint(
                        id = "last-fix",
                        latitude = lastLat,
                        longitude = lastLon,
                        title = runtime.selectedTrackerName.takeIf { it.isNotBlank() },
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
                    val rotation = trackDirectionDegrees(trackerTrail.map { LatLng(it.latitude, it.longitude) })
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
            remoteLastPoints.values
                .filter { activeStreamedTrackerIds.isNotEmpty() && it.trackId in activeStreamedTrackerIds }
                .filter { it.trackId !in renderedTrackerIds }
                .forEach { point ->
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

        val polygons = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            buildSingleSessionAccuracyPolygons(
                marker = markers.firstOrNull { it.id == "last-fix" },
                streamedAccuracyMeters = streamedAccuracyMeters,
                fallbackAccuracyMeters = fallbackAccuracyMeters,
                allowAccuracyFallback = allowAccuracyFallback,
                centerColorHex = singleLineColorHex,
            )
        } else {
            emptyList()
        }

        return MapRenderState(
            points = markers,
            lines = lines,
            polygons = polygons,
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
        trackerColorById: Map<String, String>,
        trailOutlineColorHex: String,
        singleTrackerLineColorHex: String,
    ): List<MapRenderLine> {
        return if (
            (mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) &&
            allQueueTrailsByTracker.isNotEmpty()
        ) {
            buildAllQueueLines(allQueueTrailsByTracker, trackerColorById, trailOutlineColorHex)
        } else {
            buildSegmentedLines(
                lineIdPrefix = "tracker-trail",
                points = effectiveTrail.map { it.latitude to it.longitude },
                lineColorHex = singleTrackerLineColorHex,
                outlineColorHex = trailOutlineColorHex,
            )
        }
    }

    private fun buildAllQueueLines(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trackerColorById: Map<String, String>,
        trailOutlineColorHex: String,
    ): List<MapRenderLine> {
        return allQueueTrailsByTracker.entries
            .sortedBy { it.key }
            .flatMap { (trackerId, queuedLocations) ->
                val color = normalizeColor(trackerColorById[trackerId]) ?: DEFAULT_MULTI_TRACK_LINE_COLOR_HEX
                buildSegmentedLines(
                    lineIdPrefix = "all-track-$trackerId",
                    points = queuedLocations.map { it.latitude to it.longitude },
                    lineColorHex = color,
                    outlineColorHex = trailOutlineColorHex,
                )
            }
    }

    private fun buildSegmentedLines(
        lineIdPrefix: String,
        points: List<Pair<Double, Double>>,
        lineColorHex: String,
        outlineColorHex: String,
    ): List<MapRenderLine> {
        val segments = geoVaultSplitTrackByDistance(points, MAX_TRACK_JUMP_METERS)
        return segments.mapIndexed { index, segment ->
            MapRenderLine(
                id = "$lineIdPrefix-$index",
                coordinates = segment,
                lineColorHex = lineColorHex,
                outlineColorHex = outlineColorHex
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
        val prev = validPoints[validPoints.size - 2]
        val last = validPoints.last()
        val dLon = last.longitude - prev.longitude
        val dLat = last.latitude - prev.latitude
        if (dLon == 0.0 && dLat == 0.0) return 0f
        return (Math.atan2(dLon, dLat) * 180.0 / Math.PI).toFloat()
    }

    private fun isValidPoint(point: LatLng): Boolean {
        return point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0
    }

    private fun buildSingleSessionAccuracyPolygons(
        marker: MapRenderPoint?,
        streamedAccuracyMeters: Float?,
        fallbackAccuracyMeters: Float?,
        allowAccuracyFallback: Boolean,
        centerColorHex: String,
    ): List<MapRenderPolygon> {
        val center = marker ?: return emptyList()
        val radiusMeters = AccuracyRadiusPolicy.resolveAccuracyRadiusMeters(
            AccuracyRadiusInput(
                streamedAccuracyMeters = streamedAccuracyMeters,
                fallbackAccuracyMeters = fallbackAccuracyMeters,
                allowFallback = allowAccuracyFallback,
            )
        )
        if (radiusMeters <= 0.0) return emptyList()
        val ring = AccuracyGeometryBuilder.buildAccuracyRing(
            center = LatLon(center.latitude, center.longitude),
            radiusMeters = radiusMeters,
        )
        if (ring.isEmpty()) return emptyList()
        val fillColorHex = withAlpha(centerColorHex, 0x40)
        return listOf(
            MapRenderPolygon(
                id = "last-fix-accuracy",
                rings = listOf(ring.map { it.lat to it.lon }),
                fillColorHex = fillColorHex,
                outlineColorHex = fillColorHex,
            )
        )
    }

    private fun withAlpha(colorHex: String, alpha: Int): String {
        val normalized = colorHex.removePrefix("#")
        val safeHex = if (normalized.length == 6) normalized else TrackerMapIconIds.DEFAULT_COLOR_HEX.removePrefix("#")
        val r = safeHex.substring(0, 2).toInt(16)
        val g = safeHex.substring(2, 4).toInt(16)
        val b = safeHex.substring(4, 6).toInt(16)
        val a = alpha.coerceIn(0, 255) / 255f
        // Use explicit rgba() to avoid 8-digit hex parsing differences in map style engines.
        return "rgba($r,$g,$b,$a)"
    }
}

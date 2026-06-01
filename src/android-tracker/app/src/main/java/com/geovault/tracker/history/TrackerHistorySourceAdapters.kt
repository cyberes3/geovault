package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.WireTimestampNormalizer
import com.geovault.tracker.presentation.TrackerMapPointStartTimestampParser

object TrackerHistorySourceAdapters {
    private const val TAG = "TrackerHistorySourceAdapters"
    private const val KPH_TO_MPS = 3.6f

    fun filteredServerTrunk(
        tracker: Tracker,
        fallbackWindow: TrackerHistoryWindow = tracker.historyWindowFromSettings(),
        fetchedAtMs: Long = System.currentTimeMillis(),
    ): TrackerHistorySourceBatch {
        val trackerId = tracker.id.trim()
        val coordinates = tracker.geometry?.coordinates.orEmpty()
        val pointParams = tracker.point_params.orEmpty()
        val window = fallbackWindow
        TrackerHistoryWindowResolver.logStatusWindowMismatchIfNeeded(
            trackerId = trackerId,
            settingsWindow = fallbackWindow,
            statusWindowKey = tracker.geometry_status?.window,
        )
        val timestamps = resolveGeometryTimestamps(coordinates, fetchedAtMs)
        val points = coordinates.mapIndexedNotNull { index, coord ->
            val lon = coord.getOrNull(0) ?: return@mapIndexedNotNull null
            val lat = coord.getOrNull(1) ?: return@mapIndexedNotNull null
            if (!lat.isFinite() || !lon.isFinite()) return@mapIndexedNotNull null
            val params = pointParams.getOrNull(index).orEmpty()
            TrackerHistoryPoint(
                trackerId = trackerId,
                timestampMs = timestamps[index],
                latitude = lat,
                longitude = lon,
                accuracy = params.floatValue("acc"),
                speed = params.speedMpsFromParams(),
                bearing = params.floatValue("bearing"),
                satellites = params.intValue("sat"),
                startTimestampMs = WireTimestampNormalizer.normalizeToMilliseconds(params["starttimestamp"]),
                provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                rowId = -(index + 1L),
            )
        }
        val status = tracker.geometry_status
        val complete = status?.is_truncated != true
        GeoVaultCaptureLog.i(
            TAG,
            "map_update source_batch server_trunk tracker=$trackerId window=${window.normalizedKey} " +
                "points=${points.size} returned=${status?.returned_count ?: coordinates.size} " +
                "filtered=${status?.total_filtered_count ?: coordinates.size} complete=$complete"
        )
        return TrackerHistorySourceBatch(
            trackerId = trackerId,
            window = window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = points,
            fetchedAtMs = fetchedAtMs,
            generation = tracker.updated_at ?: fetchedAtMs,
            complete = complete,
            skipRenderWindowFilter = TrackerHistoryRenderWindowPolicy.shouldSkipRenderWindowFilter(
                complete = complete,
                degradedLocalOnly = false,
                window = window,
                geometryStatusWindow = status?.window,
            ),
        )
    }

    fun localQueueOverlay(
        trackerId: String,
        window: TrackerHistoryWindow,
        queuedLocations: List<QueuedLocation>,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ): TrackerHistorySourceBatch {
        GeoVaultCaptureLog.d(
            TAG,
            "map_update source_batch local_queue tracker=${trackerId.trim()} window=${window.normalizedKey} " +
                "points=${queuedLocations.size}",
        )
        val normalizedTrackerId = trackerId.trim()
        val points = queuedLocations
            .filter { it.trackerId.trim() == normalizedTrackerId }
            .map {
                TrackerHistoryPoint.fromQueuedLocation(
                    point = it,
                    provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                )
            }
        return TrackerHistorySourceBatch(
            trackerId = normalizedTrackerId,
            window = window,
            sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
            points = points,
            fetchedAtMs = fetchedAtMs,
        )
    }

    fun liveOverlay(
        event: TrackPointEvent,
        window: TrackerHistoryWindow,
        activeSessionStartMs: Long?,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ): TrackerHistorySourceBatch {
        val sourceKind = when (event.source) {
            TrackPointSource.LOCAL_GPS -> TrackerHistorySourceKind.LOCAL_LIVE
            TrackPointSource.REMOTE_STREAM -> TrackerHistorySourceKind.REMOTE_STREAM
        }
        val resolvedSessionStart = TrackerMapPointStartTimestampParser.parse(event.propsJson)
            ?: activeSessionStartMs
        return TrackerHistorySourceBatch(
            trackerId = event.trackId.trim(),
            window = window,
            sourceKind = sourceKind,
            points = listOf(
                TrackerHistoryPoint.fromTrackPointEvent(
                    event = event,
                    startTimestampMs = resolvedSessionStart,
                ),
            ),
            fetchedAtMs = fetchedAtMs,
        )
    }

    fun degradedLocalOnlyTrunk(
        trackerId: String,
        window: TrackerHistoryWindow,
        queuedLocations: List<QueuedLocation>,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ): TrackerHistorySourceBatch {
        val normalizedTrackerId = trackerId.trim()
        val points = queuedLocations
            .filter { it.trackerId.trim() == normalizedTrackerId }
            .map {
                TrackerHistoryPoint.fromQueuedLocation(
                    point = it,
                    provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                )
            }
        GeoVaultCaptureLog.w(
            TAG,
            "map_update source_batch degraded_local_only tracker=$normalizedTrackerId window=${window.normalizedKey} " +
                "points=${points.size}",
        )
        return TrackerHistorySourceBatch(
            trackerId = normalizedTrackerId,
            window = window,
            sourceKind = TrackerHistorySourceKind.DEGRADED_LOCAL_ONLY,
            points = points,
            fetchedAtMs = fetchedAtMs,
            complete = false,
            degradedLocalOnly = true,
        )
    }

    fun runtimeHeadOverlay(
        point: QueuedLocation,
        window: TrackerHistoryWindow,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ): TrackerHistorySourceBatch {
        return TrackerHistorySourceBatch(
            trackerId = point.trackerId.trim(),
            window = window,
            sourceKind = TrackerHistorySourceKind.RUNTIME_HEAD,
            points = listOf(
                TrackerHistoryPoint.fromQueuedLocation(
                    point = point,
                    provenance = TrackerHistoryProvenance.RUNTIME_HEAD,
                )
            ),
            fetchedAtMs = fetchedAtMs,
        )
    }

    private fun Tracker.historyWindowFromSettings(): TrackerHistoryWindow {
        val key = settings?.get("recent_data_window") as? String
        return TrackerHistoryWindow(key ?: TrackerHistoryWindow.KEY_ALL)
    }

    private fun resolveGeometryTimestamps(
        coordinates: List<List<Double>>,
        fallbackAnchorMs: Long,
    ): List<Long> {
        val parsed = coordinates.map { coord ->
            coord.getOrNull(2)
                ?.toLong()
                ?.let(WireTimestampNormalizer::normalizeToMilliseconds)
        }
        val fallbackBase = parsed.filterNotNull().maxOrNull() ?: fallbackAnchorMs
        return parsed.mapIndexed { index, timestampMs ->
            timestampMs ?: (fallbackBase + index + 1L)
        }
    }

    /** Backend/GPSLogger store speed as `spd_kph`; [QueuedLocation.speed] is m/s. */
    private fun Map<String, Any?>.speedMpsFromParams(): Float? {
        val kph = floatValue("spd_kph") ?: return null
        return (kph / KPH_TO_MPS).takeIf { it.isFinite() }
    }

    private fun Map<String, Any?>.floatValue(key: String): Float? {
        return when (val value = this[key]) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private fun Map<String, Any?>.intValue(key: String): Int? {
        return when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}

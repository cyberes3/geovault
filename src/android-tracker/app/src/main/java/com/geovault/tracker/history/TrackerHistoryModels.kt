package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import java.util.Locale
import kotlin.math.roundToLong

enum class TrackerHistoryProvenance {
    SERVER_GEOMETRY,
    LOCAL_QUEUE,
    LOCAL_LIVE,
    REMOTE_STREAM,
}

enum class TrackerHistorySourceKind {
    FILTERED_SERVER_TRUNK,
    LOCAL_QUEUE,
    LOCAL_LIVE,
    REMOTE_STREAM,
    DEGRADED_LOCAL_ONLY,
}

data class TrackerHistoryWindow(
    val key: String = KEY_ALL,
) {
    val normalizedKey: String = key.trim().lowercase(Locale.US).ifBlank { KEY_ALL }
    val rollingDurationMs: Long? = ROLLING_WINDOWS_MS[normalizedKey]

    val isAll: Boolean get() = normalizedKey == KEY_ALL
    val isCurrentSession: Boolean get() = normalizedKey == KEY_CURRENT_SESSION
    val isSession: Boolean get() = normalizedKey == KEY_SESSION
    val isRolling: Boolean get() = rollingDurationMs != null

    companion object {
        const val KEY_ALL = "all"
        const val KEY_CURRENT_SESSION = "current_session"
        const val KEY_SESSION = "session"

        private const val MS_PER_MIN = 60_000L
        private const val MS_PER_HOUR = 60L * MS_PER_MIN
        private const val MS_PER_DAY = 24L * MS_PER_HOUR
        private const val MS_PER_WEEK = 7L * MS_PER_DAY
        private const val MS_PER_MONTH = 30L * MS_PER_DAY

        private val ROLLING_WINDOWS_MS = mapOf(
            "1min" to MS_PER_MIN,
            "1h" to MS_PER_HOUR,
            "1d" to MS_PER_DAY,
            "1w" to MS_PER_WEEK,
            "1m" to MS_PER_MONTH,
        )
    }
}

data class TrackerHistoryKey(
    val trackerId: String,
    val window: TrackerHistoryWindow,
) {
    val normalizedTrackerId: String = trackerId.trim()
}

data class TrackerHistoryPointKey(
    val trackerId: String,
    val timestampMs: Long,
    val latitudeE7: Long,
    val longitudeE7: Long,
    val startTimestampMs: Long?,
) {
    companion object {
        fun from(
            trackerId: String,
            timestampMs: Long,
            latitude: Double,
            longitude: Double,
            startTimestampMs: Long?,
        ): TrackerHistoryPointKey {
            return TrackerHistoryPointKey(
                trackerId = trackerId.trim(),
                timestampMs = timestampMs,
                latitudeE7 = (latitude * 10_000_000.0).roundToLong(),
                longitudeE7 = (longitude * 10_000_000.0).roundToLong(),
                startTimestampMs = startTimestampMs,
            )
        }
    }
}

data class TrackerHistoryPoint(
    val trackerId: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
    val satellites: Int? = null,
    val distanceMeters: Float? = null,
    val startTimestampMs: Long? = null,
    val provenance: TrackerHistoryProvenance,
    val rowId: Long = 0L,
) {
    val key: TrackerHistoryPointKey
        get() = TrackerHistoryPointKey.from(
            trackerId = trackerId,
            timestampMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            startTimestampMs = startTimestampMs,
        )

    fun toQueuedLocation(): QueuedLocation {
        return QueuedLocation(
            id = rowId,
            trackerId = trackerId,
            time = timestampMs,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = speed,
            bearing = bearing,
            accuracy = accuracy,
            sat = satellites,
            prov = provenance.toQueuedLocationProvider(),
            dist = distanceMeters,
            startTimestampMs = startTimestampMs,
        )
    }

    companion object {
        fun fromQueuedLocation(
            point: QueuedLocation,
            provenance: TrackerHistoryProvenance,
        ): TrackerHistoryPoint {
            return TrackerHistoryPoint(
                trackerId = point.trackerId.trim(),
                timestampMs = point.time,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitude,
                speed = point.speed,
                bearing = point.bearing,
                accuracy = point.accuracy,
                satellites = point.sat,
                distanceMeters = point.dist,
                startTimestampMs = point.startTimestampMs,
                provenance = provenance,
                rowId = point.id,
            )
        }

        fun fromTrackPoint(
            event: TrackPoint,
            startTimestampMs: Long?,
        ): TrackerHistoryPoint {
            val provenance = when (event.provenance) {
                TrackPointSource.LOCAL_GPS -> TrackerHistoryProvenance.LOCAL_LIVE
                TrackPointSource.REMOTE_STREAM -> TrackerHistoryProvenance.REMOTE_STREAM
            }
            return TrackerHistoryPoint(
                trackerId = event.trackerId.trim(),
                timestampMs = event.timeMs,
                latitude = event.latitude,
                longitude = event.longitude,
                accuracy = event.accuracyMeters,
                speed = event.gpsSpeedMps,
                bearing = event.gpsBearingDeg,
                startTimestampMs = startTimestampMs,
                provenance = provenance,
            )
        }
    }
}

data class TrackerHistorySourceBatch(
    val trackerId: String,
    val window: TrackerHistoryWindow,
    val sourceKind: TrackerHistorySourceKind,
    val points: List<TrackerHistoryPoint>,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val generation: Long = fetchedAtMs,
    val complete: Boolean = true,
    val degradedLocalOnly: Boolean = false,
    val skipRenderWindowFilter: Boolean = false,
) {
    val normalizedTrackerId: String = trackerId.trim()
}

data class TrackerHistoryClearBoundary(
    val trackerId: String,
    val clearedAtMs: Long,
    val activeSessionStartMs: Long?,
)

data class TrackerHistorySnapshot(
    val key: TrackerHistoryKey,
    val trunk: List<TrackerHistoryPoint>,
    val overlay: List<TrackerHistoryPoint>,
    val points: List<TrackerHistoryPoint>,
    val committedAtMs: Long,
    val generation: Long,
    val isLoading: Boolean = false,
    val degradedLocalOnly: Boolean = false,
    val complete: Boolean = true,
    val renderWindowFilterSkipped: Boolean = false,
)

sealed class TrackerHistoryTransactionResult {
    abstract val snapshot: TrackerHistorySnapshot

    data class Composed(override val snapshot: TrackerHistorySnapshot) : TrackerHistoryTransactionResult()
    data class ForcedEmpty(override val snapshot: TrackerHistorySnapshot) : TrackerHistoryTransactionResult()
    data class DeferredEmpty(override val snapshot: TrackerHistorySnapshot) : TrackerHistoryTransactionResult()
    data class RejectedTrunk(
        override val snapshot: TrackerHistorySnapshot,
        val rejectReason: String,
    ) : TrackerHistoryTransactionResult()
}

fun TrackerHistoryTransactionResult.kindName(): String {
    return when (this) {
        is TrackerHistoryTransactionResult.Composed -> "composed"
        is TrackerHistoryTransactionResult.ForcedEmpty -> "forced_empty_commit"
        is TrackerHistoryTransactionResult.DeferredEmpty -> "empty_snapshot_deferred"
        is TrackerHistoryTransactionResult.RejectedTrunk -> rejectReason
    }
}

fun TrackerHistoryTransactionResult.publishesSnapshot(): Boolean {
    return this is TrackerHistoryTransactionResult.Composed ||
        this is TrackerHistoryTransactionResult.ForcedEmpty
}

fun TrackerHistoryProvenance.toQueuedLocationProvider(): String {
    return when (this) {
        TrackerHistoryProvenance.SERVER_GEOMETRY -> "server_geometry"
        TrackerHistoryProvenance.LOCAL_QUEUE,
        TrackerHistoryProvenance.LOCAL_LIVE -> "local_gps"
        TrackerHistoryProvenance.REMOTE_STREAM -> "remote_stream"
    }
}

enum class TrackerHistoryRefreshCause {
    TrackerSwitch,
    ModeSwitch,
    WindowChanged,
    ColdStart,
    Resume,
    HistoryCleared,
    UploadSuccess,
    RosterChanged,
    PeriodicRecording,
    CosmeticTick,
    LivePoint,
}

data class TrackerHistoryRefreshInput(
    val cause: TrackerHistoryRefreshCause,
    val nowMs: Long,
    val lastTrunkFetchedAtMs: Long?,
    /** When set, stale checks use this tracker's last trunk fetch instead of a global timestamp. */
    val trackerIdForStaleCheck: String? = null,
    val isRecording: Boolean = false,
    val visibleRowsUploaded: Boolean = false,
    val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    companion object {
        const val DEFAULT_STALE_AFTER_MS = 60_000L
    }
}

data class TrackerHistoryRefreshDecision(
    val shouldRefresh: Boolean,
    val reason: String,
)

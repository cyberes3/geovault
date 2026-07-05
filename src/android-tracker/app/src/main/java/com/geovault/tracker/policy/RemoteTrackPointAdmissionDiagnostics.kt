package com.geovault.tracker.policy

import com.geovault.common.logging.GeoVaultCaptureLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered stages a remote (`REMOTE_STREAM`) point passes through before it ends up rendered on
 * the map. Point admission used to be decided independently across the WebSocket-level ID
 * filter, [RemoteTrackPointIngress] (now [RemoteTrackPointAdmissionPipeline]),
 * [RemoteStreamIngressPolicy]/[TrackPointPolicyEngine], and
 * [com.geovault.tracker.presentation.TrackerMapPointRouter], with no shared diagnostics — a
 * "streamed tracker not updating" report had to be root-caused by re-running a full audit
 * because no single place recorded *why* a track's points stopped advancing. Every stage now
 * reports through this one sink, keyed by (stage, reason), so that question can be answered from
 * capture logs alone.
 */
enum class RemoteTrackPointAdmissionStage {
    /** Not in the socket's current subscription set, or structurally invalid (bad lat/lon/timestamp). */
    SUBSCRIPTION_SCOPE,

    /** Track id belongs to a tracker currently being recorded locally on this device. */
    LOCAL_ECHO,

    /** [RemoteStreamIngressPolicy] / [TrackPointPolicyEngine]: staleness, ordering, positioning filter. */
    FRESHNESS_ORDERING,

    /** [com.geovault.tracker.presentation.TrackerMapPointRouter]: does the current map mode/selection want this track. */
    VISIBILITY_ROUTING,

    /** Point was committed into [com.geovault.tracker.presentation.TrackerMapUiState]. */
    PUBLISH,
}

data class RemoteTrackPointAdmissionSnapshot(
    val acceptedByStage: Map<RemoteTrackPointAdmissionStage, Long>,
    val rejectedByStageAndReason: Map<Pair<RemoteTrackPointAdmissionStage, String>, Long>,
) {
    fun acceptedCount(stage: RemoteTrackPointAdmissionStage): Long = acceptedByStage[stage] ?: 0L

    fun rejectedCount(stage: RemoteTrackPointAdmissionStage, reason: String): Long =
        rejectedByStageAndReason[stage to reason] ?: 0L

    fun totalRejected(stage: RemoteTrackPointAdmissionStage): Long =
        rejectedByStageAndReason.entries.filter { it.key.first == stage }.sumOf { it.value }
}

object RemoteTrackPointAdmissionDiagnostics {
    private const val TAG = "RemoteTrackPointAdmission"

    /** Warn at most this often per (stage, reason) bucket, regardless of volume. */
    private const val SPIKE_WARNING_INTERVAL_MS = 30_000L

    /** A bucket is only considered a "spike" worth a breadcrumb once it has this many rejections. */
    private const val SPIKE_WARNING_THRESHOLD = 20L

    private val acceptedCounters = ConcurrentHashMap<RemoteTrackPointAdmissionStage, AtomicLong>()
    private val rejectedCounters = ConcurrentHashMap<Pair<RemoteTrackPointAdmissionStage, String>, AtomicLong>()

    /**
     * Separate from [rejectedCounters]: spike detection is bucketed by (stage, reason, track)
     * rather than the plain (stage, reason) aggregate above, so a steady trickle of rejections
     * spread thinly across many different tracks can't cross the spike threshold and get
     * misattributed to whichever one track happened to be reported last -- the breadcrumb only
     * fires when one specific track is actually the one spiking.
     */
    private val perTrackRejectedCounters = ConcurrentHashMap<Triple<RemoteTrackPointAdmissionStage, String, String>, AtomicLong>()
    private val lastSpikeWarningAtMsByBucket = ConcurrentHashMap<Triple<RemoteTrackPointAdmissionStage, String, String>, AtomicLong>()

    fun recordAccepted(stage: RemoteTrackPointAdmissionStage, trackId: String) {
        acceptedCounters.getOrPut(stage) { AtomicLong(0L) }.incrementAndGet()
        GeoVaultCaptureLog.d(
            TAG,
            "map_update admission_accept stage=$stage track=${trackId.trim()}"
        )
    }

    fun recordRejected(stage: RemoteTrackPointAdmissionStage, reason: String, trackId: String) {
        val normalizedTrackId = trackId.trim()
        val bucket = stage to reason
        val rejectedCount = rejectedCounters.getOrPut(bucket) { AtomicLong(0L) }.incrementAndGet()
        GeoVaultCaptureLog.d(
            TAG,
            "map_update admission_reject stage=$stage reason=$reason track=$normalizedTrackId count=$rejectedCount"
        )
        val trackBucket = Triple(stage, reason, normalizedTrackId)
        val perTrackRejectedCount = perTrackRejectedCounters.getOrPut(trackBucket) { AtomicLong(0L) }.incrementAndGet()
        if (perTrackRejectedCount < SPIKE_WARNING_THRESHOLD) return
        warnSpikeRateLimited(trackBucket, perTrackRejectedCount)
    }

    fun snapshot(): RemoteTrackPointAdmissionSnapshot {
        return RemoteTrackPointAdmissionSnapshot(
            acceptedByStage = acceptedCounters.entries.associate { (stage, count) -> stage to count.get() },
            rejectedByStageAndReason = rejectedCounters.entries.associate { (bucket, count) -> bucket to count.get() },
        )
    }

    fun resetForTests() {
        acceptedCounters.clear()
        rejectedCounters.clear()
        perTrackRejectedCounters.clear()
        lastSpikeWarningAtMsByBucket.clear()
    }

    private fun warnSpikeRateLimited(
        bucket: Triple<RemoteTrackPointAdmissionStage, String, String>,
        rejectedCount: Long,
    ) {
        val nowMs = System.currentTimeMillis()
        val lastWarningAtMs = lastSpikeWarningAtMsByBucket.getOrPut(bucket) { AtomicLong(0L) }
        val previous = lastWarningAtMs.get()
        if (nowMs - previous < SPIKE_WARNING_INTERVAL_MS) return
        if (lastWarningAtMs.compareAndSet(previous, nowMs)) {
            runCatching {
                GeoVaultCaptureLog.w(
                    TAG,
                    "map_update admission_reject_spike stage=${bucket.first} reason=${bucket.second} " +
                        "track=${bucket.third} total=$rejectedCount"
                )
            }
        }
    }
}

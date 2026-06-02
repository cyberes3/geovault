package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.presentation.TrackerMapPointProvenancePolicy
import com.geovault.tracker.presentation.TrackerMapTrailDecimationPolicy

object TrackerHistoryTrailPreservePolicy {

    fun preserveActiveSessionTrailWhenMappedEmpty(
        mappedTrail: List<QueuedLocation>,
        stateTrail: List<QueuedLocation>,
        trackerId: String,
        activeSessionStartMs: Long?,
    ): List<QueuedLocation> {
        if (mappedTrail.isNotEmpty()) return mappedTrail
        val activeStart = activeSessionStartMs?.takeIf { it > 0L } ?: return mappedTrail
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return mappedTrail
        val activeCurrent = stateTrail.filter { point ->
            point.trackerId.trim() == normalizedId &&
                point.startTimestampMs == activeStart
        }
        if (activeCurrent.isEmpty()) return mappedTrail
        return activeCurrent
    }

    fun mergeActiveSessionCoverageIntoTrunk(
        serverTrunk: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
        trackerId: String,
        activeSessionStartMs: Long?,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty() || activeSessionStartMs == null) return serverTrunk
        val activeCurrent = currentTrail.filter { point ->
            point.trackerId.trim() == normalizedId &&
                point.startTimestampMs == activeSessionStartMs
        }
        if (activeCurrent.isEmpty()) return serverTrunk
        val loadedKeys = serverTrunk.map(::equivalentPointKey).toSet()
        val missingActive = activeCurrent.filter { point -> equivalentPointKey(point) !in loadedKeys }
        if (missingActive.isEmpty()) return serverTrunk
        val merged = (serverTrunk + missingActive)
            .distinctBy(::equivalentPointKey)
            .sortedBy { it.time }
        return TrackerMapTrailDecimationPolicy.fitToCount(merged, trailPointLimit)
    }

    fun mergeActiveSessionCoverageIntoTrunkBatch(
        batch: TrackerHistorySourceBatch,
        currentTrail: List<QueuedLocation>,
        activeSessionStartMs: Long?,
        trailPointLimit: Int,
    ): TrackerHistorySourceBatch {
        if (batch.points.isEmpty()) return batch
        val queued = batch.points.map { it.toQueuedLocation() }
        val merged = mergeActiveSessionCoverageIntoTrunk(
            serverTrunk = queued,
            currentTrail = currentTrail,
            trackerId = batch.trackerId,
            activeSessionStartMs = activeSessionStartMs,
            trailPointLimit = trailPointLimit,
        )
        if (merged.size == queued.size) return batch
        val existingByKey = batch.points.associateBy { it.key }
        val points = merged.map { loc ->
            val key = TrackerHistoryPointKey.from(
                trackerId = loc.trackerId,
                timestampMs = loc.time,
                latitude = loc.latitude,
                longitude = loc.longitude,
                startTimestampMs = loc.startTimestampMs,
            )
            existingByKey[key] ?: TrackerHistoryPoint.fromQueuedLocation(
                point = loc,
                provenance = provenanceForQueuedLocation(loc, batch.sourceKind),
            )
        }
        return batch.copy(points = points.sortedBy { it.timestampMs })
    }

    private fun provenanceForQueuedLocation(
        point: QueuedLocation,
        sourceKind: TrackerHistorySourceKind,
    ): TrackerHistoryProvenance {
        if (TrackerMapPointProvenancePolicy.isServerHistory(point)) {
            return TrackerHistoryProvenance.SERVER_GEOMETRY
        }
        return when (sourceKind) {
            TrackerHistorySourceKind.FILTERED_SERVER_TRUNK -> TrackerHistoryProvenance.SERVER_GEOMETRY
            TrackerHistorySourceKind.DEGRADED_LOCAL_ONLY,
            TrackerHistorySourceKind.LOCAL_QUEUE -> TrackerHistoryProvenance.LOCAL_QUEUE
            TrackerHistorySourceKind.LOCAL_LIVE -> TrackerHistoryProvenance.LOCAL_LIVE
            TrackerHistorySourceKind.REMOTE_STREAM -> TrackerHistoryProvenance.REMOTE_STREAM
            TrackerHistorySourceKind.RUNTIME_HEAD -> TrackerHistoryProvenance.RUNTIME_HEAD
        }
    }

    private fun equivalentPointKey(point: QueuedLocation): String {
        return listOf(
            point.trackerId.trim(),
            point.time.toString(),
            point.latitude.toString(),
            point.longitude.toString(),
        ).joinToString("|")
    }
}

package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

data class TrackerMapLocalTrailRestoreResult(
    val trail: List<QueuedLocation>,
    val changed: Boolean,
)

object TrackerMapLocalTrailRestorePolicy {
    fun restore(
        localHistoryTrail: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
        trackerId: String,
        trailPointLimit: Int,
    ): TrackerMapLocalTrailRestoreResult {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty() || localHistoryTrail.isEmpty()) {
            return TrackerMapLocalTrailRestoreResult(trail = currentTrail, changed = false)
        }

        val relevantHistory = localHistoryTrail.filter { it.trackerId.trim() == normalizedTrackerId }
        if (relevantHistory.isEmpty()) {
            return TrackerMapLocalTrailRestoreResult(trail = currentTrail, changed = false)
        }

        val relevantCurrent = currentTrail.filter { it.trackerId.trim() == normalizedTrackerId }
        val merged = mergeDeduplicated(relevantHistory, relevantCurrent)
            .sortedWith(compareBy<QueuedLocation> { it.time }.thenBy { it.id })
            .let { TrackerMapTrailDecimationPolicy.fitToCount(it, trailPointLimit) }

        return TrackerMapLocalTrailRestoreResult(
            trail = merged,
            changed = merged != currentTrail,
        )
    }

    private fun mergeDeduplicated(
        localHistoryTrail: List<QueuedLocation>,
        currentTrail: List<QueuedLocation>,
    ): List<QueuedLocation> {
        val accepted = ArrayList<QueuedLocation>(localHistoryTrail.size + currentTrail.size)
        val persistedIds = mutableSetOf<Long>()
        val transientKeys = mutableSetOf<TransientPointKey>()

        fun add(point: QueuedLocation) {
            val id = point.id.takeIf { it > 0L }
            val key = point.transientKey()
            if (id != null && id in persistedIds) return
            if (key in transientKeys) return

            accepted.add(point)
            if (id != null) persistedIds.add(id)
            transientKeys.add(key)
        }

        localHistoryTrail.forEach(::add)
        currentTrail.forEach(::add)
        return accepted
    }

    private fun QueuedLocation.transientKey(): TransientPointKey {
        return TransientPointKey(
            trackerId = trackerId.trim(),
            time = time,
            latitude = latitude,
            longitude = longitude,
            provenance = prov?.trim().orEmpty(),
        )
    }

    private data class TransientPointKey(
        val trackerId: String,
        val time: Long,
        val latitude: Double,
        val longitude: Double,
        val provenance: String,
    )
}

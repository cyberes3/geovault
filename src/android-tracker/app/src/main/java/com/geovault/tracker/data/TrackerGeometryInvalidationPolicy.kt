package com.geovault.tracker.data

data class TrackerGeometryRequestGeneration(
    val byTrackerId: Map<String, Long>,
)

object TrackerGeometryInvalidationPolicy {
    fun normalizedIds(trackerIds: Collection<String>): Set<String> {
        return trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun capture(
        trackerIds: Collection<String>,
        generationByTrackerId: Map<String, Long>,
    ): TrackerGeometryRequestGeneration {
        val captured = normalizedIds(trackerIds).associateWith { trackerId ->
            generationByTrackerId[trackerId] ?: 0L
        }
        return TrackerGeometryRequestGeneration(captured)
    }

    fun isCurrent(
        trackerId: String,
        captured: TrackerGeometryRequestGeneration,
        generationByTrackerId: Map<String, Long>,
    ): Boolean {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return false
        val capturedGeneration = captured.byTrackerId[normalized] ?: 0L
        return (generationByTrackerId[normalized] ?: 0L) == capturedGeneration
    }

    fun invalidate(
        trackerIds: Collection<String>,
        generationByTrackerId: Map<String, Long>,
    ): Map<String, Long> {
        val normalizedIds = normalizedIds(trackerIds)
        if (normalizedIds.isEmpty()) return generationByTrackerId
        val updated = generationByTrackerId.toMutableMap()
        for (trackerId in normalizedIds) {
            updated[trackerId] = (updated[trackerId] ?: 0L) + 1L
        }
        return updated
    }
}

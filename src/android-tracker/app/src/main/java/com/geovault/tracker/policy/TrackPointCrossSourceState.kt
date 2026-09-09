package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared cross-source acceptance state by track id.
 * Preserves per-track ordering/duplicate semantics across LOCAL_GPS and REMOTE_STREAM.
 */
object TrackPointCrossSourceState {
    private val lock = Any()
    private val lastAcceptedByTrack = ConcurrentHashMap<String, TrackPoint>()

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun previous(trackId: String): TrackPoint? = lastAcceptedByTrack[trackId]

    fun update(trackId: String, event: TrackPoint) {
        lastAcceptedByTrack[trackId] = event
    }

    fun resetTrack(trackId: String) {
        lastAcceptedByTrack.remove(trackId)
    }

    fun resetSource(source: TrackPointSource) {
        lastAcceptedByTrack.entries.removeIf { it.value.provenance == source }
    }

    fun resetForTests() {
        lastAcceptedByTrack.clear()
    }
}

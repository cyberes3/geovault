package com.geovault.tracker.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared cross-source acceptance state by track id.
 * Mirrors legacy per-track ordering/duplicate semantics across LOCAL_GPS and REMOTE_STREAM.
 */
object TrackPointCrossSourceState {
    private val lock = Any()
    private val lastAcceptedByTrack = ConcurrentHashMap<String, TrackPointEvent>()

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun previous(trackId: String): TrackPointEvent? = lastAcceptedByTrack[trackId]

    fun update(trackId: String, event: TrackPointEvent) {
        lastAcceptedByTrack[trackId] = event
    }

    fun resetTrack(trackId: String) {
        lastAcceptedByTrack.remove(trackId)
    }

    fun resetForTests() {
        lastAcceptedByTrack.clear()
    }
}

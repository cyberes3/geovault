package com.geovault.tracker.map

import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Sole writer of live heads. Remote stream tips and the local GPS tip share one map.
 * [TrailView.remoteLastPoints] is the published snapshot.
 */
internal class LiveHeadStore {
    private val store = GeoVaultStateStore<Map<String, TrackPoint>>(emptyMap())
    val heads: StateFlow<Map<String, TrackPoint>> = store.state
    val value: Map<String, TrackPoint> get() = store.value

    fun upsert(point: TrackPoint) {
        val id = point.trackerId.trim()
        if (id.isEmpty()) return
        val next = point.copy(trackerId = id)
        store.update { current ->
            val existing = current[id]
            if (existing != null && existing.timeMs >= next.timeMs) current
            else current + (id to next)
        }
    }

    fun replaceRemotes(remoteHeads: Map<String, TrackPoint>) {
        val remotes = remoteHeads
            .mapKeys { it.key.trim() }
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.provenance == TrackPointSource.REMOTE_STREAM }
        store.update { current ->
            current.filterValues { it.provenance == TrackPointSource.LOCAL_GPS } + remotes
        }
    }

    fun remove(trackerId: String) {
        val id = trackerId.trim()
        if (id.isEmpty()) return
        store.update { it - id }
    }

    fun clearRemotes() {
        store.update { current -> current.filterValues { it.provenance == TrackPointSource.LOCAL_GPS } }
    }

    fun clear() {
        store.replace(emptyMap())
    }
}

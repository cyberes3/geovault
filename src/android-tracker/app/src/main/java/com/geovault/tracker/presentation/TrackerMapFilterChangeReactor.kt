package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker

/**
 * Detects per-tracker `recent_data_window` changes from a stream of [Tracker] observations.
 *
 * The reactor owns a single piece of state: the last-seen window value for each tracker id.
 * Map filter behavior depends on whether a tracker the user is viewing has had its window
 * changed since the last observation — this class encapsulates that decision so the map
 * ViewModel can route on a clear sealed result instead of an ad-hoc inline memo.
 *
 * Semantics:
 *  - [seed] establishes a baseline from the current store snapshot at construction time so
 *    the very first [observe] call after startup does not spuriously classify the user's
 *    edit as a "first observation".
 *  - [observe] records the new value and returns [FilterChange.Refresh] only when a prior
 *    value existed and differs from the new one. First observations of a tracker (e.g.
 *    geometry load that races ahead of seed, freshly added tracker) silently establish a
 *    baseline and return [FilterChange.None].
 *
 * The class is intentionally synchronous and non-thread-safe — the caller (a single
 * `viewModelScope.launch` collector) provides serialization.
 */
class TrackerMapFilterChangeReactor {

    private val lastSeenWindowByTrackerId = mutableMapOf<String, String?>()

    fun seed(trackers: List<Tracker>) {
        for (tracker in trackers) {
            val id = tracker.id.trim()
            if (id.isEmpty()) continue
            lastSeenWindowByTrackerId[id] = readWindow(tracker)
        }
    }

    fun observe(tracker: Tracker): FilterChange {
        val id = tracker.id.trim()
        if (id.isEmpty()) return FilterChange.None
        val newWindow = readWindow(tracker)
        val hadPrior = lastSeenWindowByTrackerId.containsKey(id)
        val priorWindow = lastSeenWindowByTrackerId[id]
        lastSeenWindowByTrackerId[id] = newWindow
        return if (hadPrior && priorWindow != newWindow) {
            FilterChange.Refresh(id)
        } else {
            FilterChange.None
        }
    }

    private fun readWindow(tracker: Tracker): String? {
        val raw = tracker.settings?.get("recent_data_window") ?: return null
        return when (raw) {
            is String -> raw
            else -> raw.toString()
        }
    }

    sealed class FilterChange {
        data object None : FilterChange()
        data class Refresh(val trackerId: String) : FilterChange()
    }
}

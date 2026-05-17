package com.geovault.tracker.ui

import com.geovault.tracker.Tracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrackerListDateTimeFormat {
    private val format = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())

    // Tracker list rebuilds happen frequently as live points stream in. Cache last formatted
    // timestamp per ms key so that re-mapping a list of N rows after only one tracker changed
    // is O(1) per unchanged row instead of paying SimpleDateFormat.format + Date alloc every time.
    private val cache = LinkedHashMap<Long, String>(128, 0.75f, true)
    private const val MAX_CACHE_SIZE = 256

    @Synchronized
    fun formatLocal(timestampMs: Long): String {
        cache[timestampMs]?.let { return it }
        val formatted = format.format(Date(timestampMs))
        cache[timestampMs] = formatted
        if (cache.size > MAX_CACHE_SIZE) {
            val iterator = cache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return formatted
    }
}

object TrackerPointTimestamps {
    fun lastPointDataMs(tracker: Tracker): Long? {
        val coord = tracker.last_point ?: return null
        if (coord.size < 3) return null
        return normalizeEpochMs(coord[2])
    }

    fun serverMetadataUpdatedAtMs(tracker: Tracker): Long? {
        val u = tracker.updated_at ?: return null
        return normalizeEpochMs(u)
    }

    fun lastPointParamsMs(tracker: Tracker): Long? {
        val params = tracker.point_params?.lastOrNull() ?: return null
        return params.entries
            .asSequence()
            .filter { it.key.contains("timestamp", ignoreCase = true) }
            .mapNotNull { normalizeEpochMs(it.value) }
            .maxOrNull()
    }

    private fun normalizeEpochMs(value: Any?): Long? {
        val raw = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return null
        return if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }
}

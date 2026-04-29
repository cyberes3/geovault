package com.geovault.tracker.ui

import com.geovault.tracker.Tracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrackerListDateTimeFormat {
    private val format = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())

    fun formatLocal(timestampMs: Long): String {
        return format.format(Date(timestampMs))
    }
}

object TrackerPointTimestamps {
    fun lastPointDataMs(tracker: Tracker): Long? {
        val coord = tracker.last_point ?: return null
        if (coord.size < 3) return null
        val value = coord[2].toLong()
        return if (value < 1_000_000_000_000L) value * 1000L else value
    }

    fun serverMetadataUpdatedAtMs(tracker: Tracker): Long? {
        val u = tracker.updated_at ?: return null
        return if (u < 1_000_000_000_000L) u * 1000L else u
    }
}

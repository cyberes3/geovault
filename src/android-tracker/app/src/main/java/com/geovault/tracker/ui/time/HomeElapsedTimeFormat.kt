package com.geovault.tracker.ui.time

import com.geovault.common.util.ElapsedTime

/**
 * Short-form elapsed-time projection used by the Home screen "Last" stat
 * (e.g. `now`, `-25s`, `-1m`, `-1h`, `-1d`). A null or non-positive timestamp renders as
 * `now` to preserve historical Home behavior while a tracking session is starting up but
 * has not yet produced a successful upload.
 */
object HomeElapsedTimeFormat {
    fun format(lastReportedAtMs: Long?, nowMs: Long): String {
        if (lastReportedAtMs == null || lastReportedAtMs <= 0L) return "now"
        return when (val bucket = ElapsedTime.from(nowMs - lastReportedAtMs)) {
            ElapsedTime.Now -> "now"
            is ElapsedTime.Seconds -> "-${bucket.value}s"
            is ElapsedTime.Minutes -> "-${bucket.value}m"
            is ElapsedTime.Hours -> "-${bucket.value}h"
            is ElapsedTime.Days -> "-${bucket.value}d"
        }
    }
}

package com.geovault.tracker.policy

object ActiveButDeadTrackerPolicy {
    const val RECENT_METADATA_WINDOW_MS = 3L * 60 * 60 * 1000
    const val STALE_DATA_THRESHOLD_MS = 10L * 60 * 1000
    const val MIN_METADATA_AHEAD_OF_LAST_DATA_MS = 60L * 1000

    /**
     * Server row looks recently updated ([updatedAtMs] within 3h) and newer than the last
     * point by at least [MIN_METADATA_AHEAD_OF_LAST_DATA_MS], but the last data point is older
     * than 10 minutes. (Avoids flagging simply idle devices where the row and last point aged
     * together; keeps true "touched the row but GPS is old" like settings or orphan updates.)
     */
    fun isActiveButDead(
        nowMs: Long,
        updatedAtMs: Long?,
        lastDataMs: Long?,
        lastParamsMs: Long? = null,
    ): Boolean {
        if (lastDataMs == null || updatedAtMs == null) return false
        if (nowMs - lastDataMs <= STALE_DATA_THRESHOLD_MS) return false
        if (nowMs - updatedAtMs >= RECENT_METADATA_WINDOW_MS) return false
        if (nowMs < updatedAtMs) return false
        if (lastParamsMs != null && lastParamsMs <= lastDataMs) return false
        if (updatedAtMs - lastDataMs <= MIN_METADATA_AHEAD_OF_LAST_DATA_MS) return false
        return true
    }
}

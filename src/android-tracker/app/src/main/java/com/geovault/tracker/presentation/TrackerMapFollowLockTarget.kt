package com.geovault.tracker.presentation

/**
 * Follow-lock camera target. When follow lock is armed there is no displayed tracker, so the
 * owner is the user GPS puck. Tracker [liveHead] is only used when follow lock is off (live-active
 * fit zoom recenter).
 */
object TrackerMapFollowLockTarget {
    fun resolve(
        followLockEnabled: Boolean,
        puckLatitude: Double?,
        puckLongitude: Double?,
        liveHead: Pair<Double, Double>?,
    ): Pair<Double, Double>? {
        if (followLockEnabled) {
            val lat = puckLatitude ?: return null
            val lon = puckLongitude ?: return null
            return lat to lon
        }
        return liveHead
    }
}

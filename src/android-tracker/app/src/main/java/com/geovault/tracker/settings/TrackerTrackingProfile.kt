package com.geovault.tracker.settings

enum class TrackerTrackingProfile(val index: Int) {
    WALKING(0),
    BIKING(1),
    DRIVING(2),
    CUSTOM(3);

    companion object {
        @JvmStatic
        fun fromIndex(index: Int): TrackerTrackingProfile {
            return entries.firstOrNull { it.index == index } ?: BIKING
        }
    }
}

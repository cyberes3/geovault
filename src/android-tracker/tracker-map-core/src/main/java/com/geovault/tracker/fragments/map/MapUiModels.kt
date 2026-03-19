package com.geovault.tracker.fragments.map

/** Selected tracker on the map (all-trackers or group mode); used for bottom info card. */
data class SelectedMapTracker(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lastUpdateMs: Long?,
    val isOwner: Boolean,
    val hexColor: String
)

enum class MapViewContext {
    SINGLE_TRACKER,
    GROUP
}

enum class CameraPaddingMode {
    CENTERED,
    OVERLAY_AWARE
}

enum class CameraIntent {
    NONE,
    BOUNDS_FIT,
    GROUP_MEMBER_FOCUS,
    SINGLE_TRACKER_FOCUS,
    FOLLOW_LOCK
}


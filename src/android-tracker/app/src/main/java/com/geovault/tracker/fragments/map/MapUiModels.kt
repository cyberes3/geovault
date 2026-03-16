package com.geovault.tracker.fragments.map

/** Selected tracker on the map (all-trackers or group mode); used for bottom info card. */
internal data class SelectedMapTracker(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lastUpdateMs: Long?,
    val isOwner: Boolean,
    val hexColor: String
)

internal enum class MapViewContext {
    DEFAULT_TRACKER,
    SPECIFIC_TRACKER,
    GROUP
}

internal enum class CameraPaddingMode {
    CENTERED,
    OVERLAY_AWARE
}

internal enum class CameraIntent {
    NONE,
    BOUNDS_FIT,
    GROUP_MEMBER_FOCUS,
    SINGLE_TRACKER_FOCUS,
    FOLLOW_LOCK
}

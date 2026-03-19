package com.geovault.tracker.fragments.map

class ApplyCameraPolicyUseCase {
    fun forMode(
        mode: MapScreenMode,
        currentSelection: MapSelection?,
        enableFollowLock: Boolean
    ): MapCameraCommand {
        return when (mode) {
            is MapScreenMode.Single -> MapCameraCommand(
                lockMode = if (enableFollowLock) MapLockMode.TRACKER_FOLLOW else MapLockMode.NONE,
                lockNeedsInitialZoom = enableFollowLock,
                targetTrackerId = currentSelection?.trackerId,
                fitBounds = false
            )

            is MapScreenMode.GroupMode -> MapCameraCommand(
                lockMode = MapLockMode.NONE,
                targetTrackerId = currentSelection?.trackerId,
                fitBounds = true
            )

            MapScreenMode.AllTrackers -> MapCameraCommand(
                lockMode = MapLockMode.NONE,
                targetTrackerId = null,
                fitBounds = true
            )
        }
    }
}


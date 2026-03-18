package com.geovault.tracker.fragments.map

class ApplyCameraPolicyUseCase {
    fun forMode(
        mode: MapScreenMode,
        currentSelection: MapSelection?,
        enableFollowLock: Boolean
    ): MapCameraCommand {
        return when (mode) {
            is MapScreenMode.Single -> MapCameraCommand(
                followLockEnabled = enableFollowLock,
                targetTrackerId = currentSelection?.trackerId,
                fitBounds = false
            )

            is MapScreenMode.GroupMode -> MapCameraCommand(
                followLockEnabled = false,
                targetTrackerId = currentSelection?.trackerId,
                fitBounds = true
            )

            MapScreenMode.AllTrackers -> MapCameraCommand(
                followLockEnabled = false,
                targetTrackerId = null,
                fitBounds = true
            )
        }
    }
}


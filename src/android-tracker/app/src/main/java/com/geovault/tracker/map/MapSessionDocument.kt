package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.streaming.StreamIntent

data class MapCameraLock(
    val followEnabled: Boolean = false,
    val liveActiveFitEnabled: Boolean = false,
    val selectionTrackerId: String = "",
)

data class MapSurfaceFlags(
    val geometryLoading: Boolean = false,
    val batteryOptimizationHintVisible: Boolean = false,
    val bottomCardVisible: Boolean = false,
    val liveGpsPuckRequested: Boolean = false,
)

data class MapSessionDocument(
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val displayedTrackerId: String = "",
    val selectedTrackerId: String = "",
    val groupId: String = "",
    val visibleTrackerIds: Set<String> = emptySet(),
    val streamIntent: StreamIntent? = null,
    val selectionCardTrackerId: String = "",
    val cameraLock: MapCameraLock = MapCameraLock(),
    val selectionCard: TrackerMapSelectionCard? = null,
    val surface: MapSurfaceFlags = MapSurfaceFlags(),
)

internal fun MapSessionDocument.withUiState(state: TrackerMapUiState): MapSessionDocument {
    val nextMode = state.mode
    val nextDisplayed = state.displayedTrackerId
    val nextGroup = state.currentGroupId
    val viewportChanged =
        mode != nextMode || displayedTrackerId != nextDisplayed || groupId != nextGroup
    return copy(
        mode = nextMode,
        displayedTrackerId = nextDisplayed,
        groupId = nextGroup,
        selectionCardTrackerId = state.selectedMapTracker?.trackerId.orEmpty(),
        cameraLock = MapCameraLock(
            followEnabled = state.followLockEnabled,
            liveActiveFitEnabled = state.liveActiveFitEnabled,
            selectionTrackerId = state.selectionLockTrackerId.trim(),
        ),
        selectionCard = state.selectedMapTracker.takeIf { state.isBottomCardVisible },
        surface = MapSurfaceFlags(
            geometryLoading = state.isGeometryLoading,
            batteryOptimizationHintVisible = state.batteryOptimizationHintVisible,
            bottomCardVisible = state.isBottomCardVisible,
            liveGpsPuckRequested = if (viewportChanged) false else surface.liveGpsPuckRequested,
        ),
    )
}

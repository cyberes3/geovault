package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import com.geovault.tracker.pipeline.TrackPointEvent

sealed class MapScreenMode {
    data object Single : MapScreenMode()
    data class GroupMode(val group: Group) : MapScreenMode()
    data object AllTrackers : MapScreenMode()
}

data class MapSelection(
    val trackerId: String,
    val trackerName: String?,
    val lat: Double,
    val lon: Double,
    val lastUpdateMs: Long?,
    val isOwner: Boolean,
    val hexColor: String?
)

data class MapTrackSnapshot(
    val tracker: Tracker,
    val coordinates: List<List<Double>>,
    val forceReplace: Boolean
)

data class MapAllTrackersSnapshot(
    val trackers: List<Tracker>,
    val coordsByTrackerId: Map<String, List<List<Double>>>,
    val fitBounds: Boolean = true,
    val fitToTrackerId: String? = null,
    val liveActiveOnlyFit: Boolean = false
)

data class MapCameraCommand(
    val lockMode: MapLockMode,
    val lockTargetLat: Double? = null,
    val lockTargetLon: Double? = null,
    val lockNeedsInitialZoom: Boolean = false,
    val targetTrackerId: String? = null,
    val fitBounds: Boolean = false
)

data class MapUiState(
    val mode: MapScreenMode = MapScreenMode.Single,
    val loading: Boolean = false,
    val displayedTrackerId: String? = null,
    val displayedTrackerName: String? = null,
    val displayedGroupName: String? = null,
    val showAllTrackers: Boolean = false,
    val lockMode: MapLockMode = MapLockMode.NONE,
    val activeStreamedTrackerIds: Set<String> = emptySet()
)

sealed class MapIntent {
    data class LoadSingleTracker(
        val trackerId: String?,
        val forceReplace: Boolean = false
    ) : MapIntent()

    data object LoadAllTrackers : MapIntent()

    data class LoadGroup(
        val group: Group,
        val zoomToTrackerId: String? = null
    ) : MapIntent()
}

sealed class MapCommand {
    data class RenderSingleTracker(val snapshot: MapTrackSnapshot) : MapCommand()
    data class RenderAllTrackers(val snapshot: MapAllTrackersSnapshot) : MapCommand()
    data class ApplyTrackPoint(val event: TrackPointEvent) : MapCommand()
    data class ApplyCameraPolicy(val command: MapCameraCommand) : MapCommand()
    data class ShowError(val message: String) : MapCommand()
}


package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

internal class MapGroupRefreshCallbacks(
    val isAdded: () -> Boolean,
    val getMap: () -> MapLibreMap?,
    val getStyle: () -> Style?,
    val setPendingGroup: (Group, String?) -> Unit,
    val setLiveActiveFitEnabled: (Boolean) -> Unit,
    val clearMultiTrackContextState: () -> Unit,
    val setMapViewContext: (MapViewContext) -> Unit,
    val setDisplayedGroupName: (String?) -> Unit,
    val setCurrentGroupForMap: (Group?) -> Unit,
    val setActiveCameraIntent: (CameraIntent) -> Unit,
    val suppressStandaloneAutoZoomForTrackerFocus: () -> Unit,
    val setShowAllTrackers: (Boolean) -> Unit,
    val clearMapSelection: () -> Unit,
    val clearAllTrackSources: () -> Unit,
    val setAllTrackLayersVisibility: (Boolean) -> Unit,
    val setAnnotationLayersVisibility: (Boolean) -> Unit,
    val clearTrackPointsAndDisplayedTracker: () -> Unit,
    val updateTrackLine: () -> Unit,
    val stopLiveTrackStreaming: () -> Unit,
    val updateTrackerLabel: () -> Unit,
    val updateZoomToLatestButtonState: () -> Unit,
    val startLiveTrackStreamingForTrackerSet: (Set<String>) -> Unit,
    val applyAllTrackersToMap: (
        trackers: List<Tracker>,
        coordsById: Map<String, List<List<Double>>>,
        map: MapLibreMap,
        style: Style,
        fitBounds: Boolean,
        fitToTrackerId: String?,
        liveActiveOnlyFit: Boolean
    ) -> Unit,
    val getLiveActiveFitEnabled: () -> Boolean,
    val getShowAllTrackers: () -> Boolean
)

internal object MapGroupRefreshHandler {
    fun refresh(
        group: Group,
        zoomToTrackerId: String?,
        context: Context,
        scope: CoroutineScope,
        callbacks: MapGroupRefreshCallbacks
    ) {
        callbacks.setLiveActiveFitEnabled(false)
        callbacks.clearMultiTrackContextState()
        val map = callbacks.getMap()
        if (map == null) {
            callbacks.setPendingGroup(group, zoomToTrackerId)
            return
        }
        val style = callbacks.getStyle() ?: return
        callbacks.setMapViewContext(MapViewContext.GROUP)
        callbacks.setDisplayedGroupName(group.name)
        callbacks.setCurrentGroupForMap(group)
        callbacks.setActiveCameraIntent(
            if (zoomToTrackerId.isNullOrBlank()) CameraIntent.BOUNDS_FIT else CameraIntent.GROUP_MEMBER_FOCUS
        )
        if (!zoomToTrackerId.isNullOrBlank()) {
            callbacks.suppressStandaloneAutoZoomForTrackerFocus()
        }
        callbacks.setShowAllTrackers(true)
        callbacks.clearMapSelection()
        callbacks.clearAllTrackSources()
        callbacks.setAllTrackLayersVisibility(false)
        callbacks.setAnnotationLayersVisibility(false)
        callbacks.clearTrackPointsAndDisplayedTracker()
        callbacks.updateTrackLine()
        callbacks.stopLiveTrackStreaming()
        callbacks.updateTrackerLabel()
        callbacks.updateZoomToLatestButtonState()
        val trackIds = group.track_ids?.toSet() ?: emptySet()
        if (trackIds.isEmpty()) {
            callbacks.stopLiveTrackStreaming()
            callbacks.setAllTrackLayersVisibility(true)
            return
        }
        TrackerRepository.getTrackers(context, forceRefresh = false) { list ->
            if (!callbacks.isAdded()) return@getTrackers
            val allTrackers = list ?: emptyList()
            val trackers = allTrackers.filter { it.id in trackIds }
            if (trackers.isEmpty()) {
                callbacks.stopLiveTrackStreaming()
                callbacks.setAllTrackLayersVisibility(true)
                return@getTrackers
            }
            callbacks.startLiveTrackStreamingForTrackerSet(trackers.map { it.id }.toSet())
            val useLiveActiveFit = callbacks.getLiveActiveFitEnabled() && zoomToTrackerId.isNullOrBlank()
            callbacks.applyAllTrackersToMap(
                trackers,
                emptyMap(),
                map,
                style,
                true,
                zoomToTrackerId,
                useLiveActiveFit
            )
            TrackerRepository.getTrackersGeometry(context, trackers.map { it.id }, allData = true) { fullTrackers ->
                scope.launch {
                    if (!callbacks.isAdded() || !callbacks.getShowAllTrackers()) return@launch
                    val coordsById = mutableMapOf<String, List<List<Double>>>()
                    (fullTrackers ?: emptyList()).forEach { full ->
                        val coords = full.geometry?.coordinates ?: emptyList()
                        if (coords.isNotEmpty()) {
                            coordsById[full.id] = coords
                        }
                    }
                    val currentMap = callbacks.getMap() ?: return@launch
                    val currentStyle = callbacks.getStyle() ?: return@launch
                    callbacks.applyAllTrackersToMap(
                        trackers,
                        coordsById,
                        currentMap,
                        currentStyle,
                        false,
                        zoomToTrackerId,
                        false
                    )
                }
            }
        }
    }
}

package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker
import org.maplibre.android.geometry.LatLng

internal class MapLiveStreamPointCallbacks(
    val getShowAllTrackers: () -> Boolean,
    val getMapViewContext: () -> MapViewContext,
    val getActiveStreamedTrackerIds: () -> Set<String>,
    val getLastAllTrackers: () -> List<Tracker>?,
    val getTrackerBaseCoordsForMultiContext: (Tracker, String) -> MutableList<List<Double>>,
    val setMultiTrackCoordsCache: (String, MutableList<List<Double>>) -> Unit,
    val setLastKnownUpdateTimeMsByTrackerId: (String, Long) -> Unit,
    val getSelectedMapTracker: () -> SelectedMapTracker?,
    val onUpdateSelectedMapTracker: (String, Double, Double, Long) -> Unit,
    val onRecenterFollowLock: (LatLng) -> Unit,
    val getShowMyLocationEnabled: () -> Boolean,
    val getLockMode: () -> MapLockMode,
    val scheduleDebouncedMultiTrackRender: () -> Unit,
    val updateMapSelectionUi: () -> Unit,
    val getDisplayedTrackerId: () -> String?,
    val getIsAdded: () -> Boolean,
    val setLastStreamedPointTimeMs: (Long?) -> Unit,
    val updateStreamingUi: () -> Unit,
    val addTrackPoint: (LatLng, Long) -> Unit,
    val scheduleTrackLineUpdate: () -> Unit,
    val updateZoomToLatestButtonState: () -> Unit,
    val scheduleDebouncedSingleLiveFit: () -> Unit,
    val getLiveActiveFitEnabled: () -> Boolean
)

internal object MapLiveStreamPointHandler {
    private fun isValidLatLon(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }

    fun applyLiveStreamPoint(
        trackId: String,
        lat: Double,
        lon: Double,
        timestampMs: Long,
        callbacks: MapLiveStreamPointCallbacks
    ) {
        if (!isValidLatLon(lat, lon)) return
        val normalizedTimestampMs = MapCoordinateUtils.normalizeTimestampToMs(timestampMs)
        val isMultiContext = MapLiveStreamHandler.isMultiContext(callbacks.getShowAllTrackers(), callbacks.getMapViewContext())

        if (isMultiContext) {
            if (trackId !in callbacks.getActiveStreamedTrackerIds()) return
            val trackers = callbacks.getLastAllTrackers() ?: return
            val tracker = trackers.firstOrNull { it.id == trackId } ?: return
            val trackerCoords = callbacks.getTrackerBaseCoordsForMultiContext(tracker, trackId)
            val accepted = MapCoordinateUtils.appendStreamedPointIfNewer(trackerCoords, lon, lat, normalizedTimestampMs)
            if (!accepted) return
            callbacks.setMultiTrackCoordsCache(trackId, trackerCoords)
            callbacks.setLastKnownUpdateTimeMsByTrackerId(trackId, normalizedTimestampMs)
            val selection = callbacks.getSelectedMapTracker()
            if (selection?.id == trackId) {
                callbacks.onUpdateSelectedMapTracker(trackId, lat, lon, normalizedTimestampMs)
                if (!callbacks.getShowMyLocationEnabled() && callbacks.getLockMode() == MapLockMode.TRACKER_FOLLOW) {
                    callbacks.onRecenterFollowLock(LatLng(lat, lon))
                }
            }
            callbacks.scheduleDebouncedMultiTrackRender()
            if (selection?.id == trackId) {
                callbacks.updateMapSelectionUi()
            }
            return
        }

        if (!MapLiveStreamHandler.shouldHandleSingleTrackPoint(trackId, callbacks.getDisplayedTrackerId())) return
        callbacks.setLastStreamedPointTimeMs(normalizedTimestampMs)
        callbacks.setLastKnownUpdateTimeMsByTrackerId(trackId, normalizedTimestampMs)
        val selection = callbacks.getSelectedMapTracker()
        if (selection?.id == trackId) {
            callbacks.onUpdateSelectedMapTracker(trackId, lat, lon, normalizedTimestampMs)
            callbacks.updateMapSelectionUi()
        }
        if (callbacks.getIsAdded()) callbacks.updateStreamingUi()
        callbacks.addTrackPoint(LatLng(lat, lon), normalizedTimestampMs)
        callbacks.scheduleTrackLineUpdate()
        callbacks.updateZoomToLatestButtonState()
        if (!callbacks.getShowMyLocationEnabled() && callbacks.getLockMode() == MapLockMode.TRACKER_FOLLOW) {
            callbacks.onRecenterFollowLock(LatLng(lat, lon))
        }
        if (callbacks.getLiveActiveFitEnabled()) callbacks.scheduleDebouncedSingleLiveFit()
    }
}

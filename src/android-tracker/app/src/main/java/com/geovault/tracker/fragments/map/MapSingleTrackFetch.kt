package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MapSingleTrackFetchCallbacks(
    val getScope: () -> CoroutineScope,
    val getDisplayedTrackerId: () -> String?,
    val getSelectedTrackerId: () -> String,
    val getShowAllTrackers: () -> Boolean,
    val getMapViewContext: () -> MapViewContext,
    val getTrackPointsEmpty: () -> Boolean,
    val getCoordinatesFetchInFlightTrackerId: () -> String?,
    val setCoordinatesFetchInFlightTrackerId: (String?) -> Unit,
    val getCoordinatesFetchInFlightEpoch: () -> Long?,
    val setCoordinatesFetchInFlightEpoch: (Long?) -> Unit,
    val getGeometryFetchInFlightTrackerId: () -> String?,
    val setGeometryFetchInFlightTrackerId: (String?) -> Unit,
    val getGeometryFetchInFlightEpoch: () -> Long?,
    val setGeometryFetchInFlightEpoch: (Long?) -> Unit,
    val setGeometryLoadingInProgress: (Boolean) -> Unit,
    val updateBottomRightSpinner: () -> Unit,
    val onSkipped: () -> Unit,
    val onSetZoomToTrackAfterLoad: (Boolean) -> Unit,
    /** Returns true if the Fragment applied the preview (so coordinator considers seeded). Optional accuracy from point_params (e.g. cached tail). */
    val onSeededFromPreview: (Tracker?, List<List<Double>>, Boolean, Float?) -> Boolean,
    /** Fragment applies coords and optionally updates label; called from repository callback. */
    val onSeededFromNetwork: (List<List<Double>>, List<Map<String, Any?>>?) -> Unit,
    val getIsAdded: () -> Boolean,
    val getTrackerRequestEpoch: () -> Long,
    val onGeometryLoaded: (Tracker?, String, Boolean) -> Unit
)

internal object MapSingleTrackFetch {
    private fun isSingleTrackerContext(callbacks: MapSingleTrackFetchCallbacks): Boolean {
        return !callbacks.getShowAllTrackers() && callbacks.getMapViewContext() != MapViewContext.GROUP
    }

    private fun isActiveTracker(callbacks: MapSingleTrackFetchCallbacks, trackerId: String): Boolean {
        val selectedId = callbacks.getSelectedTrackerId()
        val activeTrackerId = MapDataLoader.resolveActiveTrackerId(callbacks.getDisplayedTrackerId(), selectedId)
        return activeTrackerId == trackerId
    }

    fun loadHistory(context: Context, callbacks: MapSingleTrackFetchCallbacks) {
        val requestEpoch = callbacks.getTrackerRequestEpoch()
        val selectedTrackerId = callbacks.getSelectedTrackerId()
        val trackerId = MapDataLoader.resolveActiveTrackerId(callbacks.getDisplayedTrackerId(), selectedTrackerId)

        if (trackerId.isEmpty() || !isSingleTrackerContext(callbacks)) {
            callbacks.onSkipped()
            return
        }

        TrackerRepository.getTrackers(context, forceRefresh = false) { }
        seedFromCacheOrTail(context, trackerId, null, true, callbacks, requestEpoch)

        if (MapDataLoader.shouldAutoZoomSingleTracker(callbacks.getTrackPointsEmpty())) {
            callbacks.onSetZoomToTrackAfterLoad(true)
        }
        fetchFullGeometry(context, trackerId, false, callbacks, requestEpoch)
    }

    fun seedFromCacheOrTail(
        context: Context,
        trackerId: String,
        initialTracker: Tracker?,
        allowCoordinatesNetwork: Boolean,
        callbacks: MapSingleTrackFetchCallbacks,
        requestEpoch: Long = callbacks.getTrackerRequestEpoch()
    ) {
        if (MapDataLoader.shouldSkipSeedTrack(trackerId, callbacks.getShowAllTrackers(), callbacks.getMapViewContext())) return

        var seeded = false
        val trackerPreview = initialTracker ?: TrackerRepository.getTrackerFromCache(trackerId)
        if (trackerPreview != null) {
            seeded = callbacks.onSeededFromPreview(trackerPreview, trackerPreview.geometry?.coordinates ?: emptyList(), callbacks.getTrackPointsEmpty(), null)
        }

        if (!seeded) {
            val cachedGeometry = TrackerRepository.getTrackerGeometryFromCache(trackerId)
            if (cachedGeometry != null) {
                seeded = callbacks.onSeededFromPreview(cachedGeometry, cachedGeometry.geometry?.coordinates ?: emptyList(), true, null)
            }
        }

        if (!seeded) {
            val cachedTail = TrackerRepository.getTrackerCoordinatesFromCache(trackerId)
            if (cachedTail != null) {
                val acc = cachedTail.point_params?.lastOrNull()?.get("acc")?.let { (it as? Number)?.toFloat() }?.takeIf { it > 0f }
                seeded = callbacks.onSeededFromPreview(null, cachedTail.coordinates, true, acc)
            }
        }

        if (seeded) return

        if (!allowCoordinatesNetwork) return
        val inFlightCoordinatesId = callbacks.getCoordinatesFetchInFlightTrackerId()
        val inFlightCoordinatesEpoch = callbacks.getCoordinatesFetchInFlightEpoch()
        if (inFlightCoordinatesId == trackerId && inFlightCoordinatesEpoch == requestEpoch) return
        callbacks.setCoordinatesFetchInFlightTrackerId(trackerId)
        callbacks.setCoordinatesFetchInFlightEpoch(requestEpoch)
        TrackerRepository.getTrackerCoordinates(context, trackerId) { response ->
            callbacks.getScope().launch {
                if (callbacks.getCoordinatesFetchInFlightTrackerId() == trackerId &&
                    callbacks.getCoordinatesFetchInFlightEpoch() == requestEpoch
                ) {
                    callbacks.setCoordinatesFetchInFlightTrackerId(null)
                    callbacks.setCoordinatesFetchInFlightEpoch(null)
                }
                if (callbacks.getTrackerRequestEpoch() != requestEpoch) return@launch
                if (!callbacks.getIsAdded() || !isSingleTrackerContext(callbacks)) return@launch
                if (!isActiveTracker(callbacks, trackerId)) return@launch
                val coords = response?.coordinates ?: return@launch
                callbacks.onSeededFromNetwork(coords, response.point_params)
            }
        }
    }

    fun fetchFullGeometry(
        context: Context,
        trackerId: String,
        forceReplace: Boolean,
        callbacks: MapSingleTrackFetchCallbacks,
        requestEpoch: Long = callbacks.getTrackerRequestEpoch()
    ) {
        val inFlightGeometryId = callbacks.getGeometryFetchInFlightTrackerId()
        val inFlightGeometryEpoch = callbacks.getGeometryFetchInFlightEpoch()
        if (inFlightGeometryId == trackerId && inFlightGeometryEpoch == requestEpoch) return
        callbacks.setGeometryFetchInFlightTrackerId(trackerId)
        callbacks.setGeometryFetchInFlightEpoch(requestEpoch)
        callbacks.setGeometryLoadingInProgress(true)
        callbacks.updateBottomRightSpinner()

        TrackerRepository.getTrackerGeometry(context, trackerId) { tracker ->
            callbacks.getScope().launch {
                if (callbacks.getGeometryFetchInFlightTrackerId() == trackerId &&
                    callbacks.getGeometryFetchInFlightEpoch() == requestEpoch
                ) {
                    callbacks.setGeometryFetchInFlightTrackerId(null)
                    callbacks.setGeometryFetchInFlightEpoch(null)
                    callbacks.setGeometryLoadingInProgress(false)
                    callbacks.updateBottomRightSpinner()
                }
                if (callbacks.getTrackerRequestEpoch() != requestEpoch) return@launch
                if (!callbacks.getIsAdded()) return@launch
                if (!isSingleTrackerContext(callbacks)) return@launch
                if (!isActiveTracker(callbacks, trackerId)) return@launch
                callbacks.onGeometryLoaded(tracker, trackerId, forceReplace)
            }
        }
    }

    fun cancelGeometry() {
        TrackerRepository.cancelGeometryRequest()
    }
}

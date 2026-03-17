package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class InFlightRequestToken(
    val trackerId: String? = null,
    val epoch: Long? = null
)

internal class MapSingleTrackFetchCallbacks(
    val getScope: () -> CoroutineScope,
    val getSelectedTrackerId: () -> String,
    val getIsSingleTrackerContext: () -> Boolean,
    val getActiveTrackerId: () -> String,
    val getTrackPointsEmpty: () -> Boolean,
    val getCoordinatesFetchToken: () -> InFlightRequestToken,
    val setCoordinatesFetchToken: (InFlightRequestToken) -> Unit,
    val getGeometryFetchToken: () -> InFlightRequestToken,
    val setGeometryFetchToken: (InFlightRequestToken) -> Unit,
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
        return callbacks.getIsSingleTrackerContext()
    }

    private fun isActiveTracker(callbacks: MapSingleTrackFetchCallbacks, trackerId: String): Boolean {
        val activeTrackerId = callbacks.getActiveTrackerId()
        return activeTrackerId == trackerId
    }

    fun loadHistory(
        context: Context,
        callbacks: MapSingleTrackFetchCallbacks,
        trackerIdOverride: String? = null,
        initialTracker: Tracker? = null
    ) {
        val requestEpoch = callbacks.getTrackerRequestEpoch()
        val selectedTrackerId = callbacks.getSelectedTrackerId()
        val trackerId = trackerIdOverride?.takeIf { it.isNotEmpty() }
            ?: callbacks.getActiveTrackerId().ifEmpty { selectedTrackerId }

        if (trackerId.isEmpty() || !isSingleTrackerContext(callbacks)) {
            callbacks.onSkipped()
            return
        }

        TrackerRepository.getTrackers(context, forceRefresh = false) { }
        seedFromCacheOrTail(context, trackerId, initialTracker, true, callbacks, requestEpoch)

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
        if (trackerId.isEmpty() || !isSingleTrackerContext(callbacks)) return

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
        val inFlightCoordinates = callbacks.getCoordinatesFetchToken()
        if (inFlightCoordinates.trackerId == trackerId && inFlightCoordinates.epoch == requestEpoch) return
        callbacks.setCoordinatesFetchToken(InFlightRequestToken(trackerId = trackerId, epoch = requestEpoch))
        TrackerRepository.getTrackerCoordinates(context, trackerId) { response ->
            callbacks.getScope().launch {
                val currentToken = callbacks.getCoordinatesFetchToken()
                if (currentToken.trackerId == trackerId && currentToken.epoch == requestEpoch) {
                    callbacks.setCoordinatesFetchToken(InFlightRequestToken())
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
        val inFlightGeometry = callbacks.getGeometryFetchToken()
        if (inFlightGeometry.trackerId == trackerId && inFlightGeometry.epoch == requestEpoch) return
        callbacks.setGeometryFetchToken(InFlightRequestToken(trackerId = trackerId, epoch = requestEpoch))
        callbacks.setGeometryLoadingInProgress(true)
        callbacks.updateBottomRightSpinner()

        TrackerRepository.getTrackerGeometry(context, trackerId) { tracker ->
            callbacks.getScope().launch {
                val currentToken = callbacks.getGeometryFetchToken()
                if (currentToken.trackerId == trackerId && currentToken.epoch == requestEpoch) {
                    callbacks.setGeometryFetchToken(InFlightRequestToken())
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

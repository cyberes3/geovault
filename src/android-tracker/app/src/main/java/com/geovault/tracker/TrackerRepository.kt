package com.geovault.tracker

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume

/**
 * Single source for tracker list and per-tracker data. Cache invalidation follows this policy:
 *
 * - **clearListCaches()**: Trackers list, groups, map visibility. Use on logout/auth failure or when
 *   list data may be stale globally.
 * - **clearSelectedTrackerCaches()**: Current tracker object, geometry, and coordinates (all data
 *   tied to the selected tracker). Use when the selected tracker is cleared, changed, deleted, or
 *   hidden; or when switching to "view" another tracker (e.g. View on map, params for non-selected)
 *   so the next load is fresh.
 * - **clearGeometryCache()**: Only geometry and coordinates. Use when geometry is invalidated but
 *   selection is unchanged (e.g. user cleared tracker history).
 */
object TrackerRepository {
    private data class TrackDataCacheKey(
        val trackerId: String
    )

    private var trackersCache: List<Tracker>? = null
    private var isFetching = false
    private val listeners = mutableListOf<(List<Tracker>?) -> Unit>()

    /** Cached map visibility; updated by getMapVisibility and patchMapVisibility. */
    @Volatile
    private var mapVisibilityCache: MapVisibilityResponse? = null

    fun getMapVisibilityCache(): MapVisibilityResponse? = mapVisibilityCache

    private var currentTrackerCache: Tracker? = null
    private var currentTrackerId: String? = null

    private fun createApi(context: Context, serverUrl: String): TrackerApi {
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
    }

    private fun errorFromResponseCode(code: Int, message: String? = null): AppError {
        return when (code) {
            400 -> AppError.Validation(message)
            401, 403 -> AppError.Unauthorized
            404 -> AppError.NotFound
            in 500..599 -> AppError.Server(code)
            else -> AppError.Unknown
        }
    }

    private fun boolResult(success: Boolean): RepositoryResult<Unit> {
        return if (success) RepositoryResult.Success(Unit) else RepositoryResult.Failure(AppError.Unknown)
    }

    fun getTrackersResult(
        context: Context,
        forceRefresh: Boolean = false,
        callback: (RepositoryResult<List<Tracker>>) -> Unit
    ) {
        if (!forceRefresh && trackersCache != null) {
            callback(RepositoryResult.Success(trackersCache ?: emptyList()))
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(RepositoryResult.Failure(AppError.MissingServerUrl))
            return
        }
        val api = createApi(context, serverUrl)
        api.getTrackers().enqueue(object : Callback<List<Tracker>> {
            override fun onResponse(call: Call<List<Tracker>>, response: Response<List<Tracker>>) {
                if (!response.isSuccessful) {
                    callback(RepositoryResult.Failure(errorFromResponseCode(response.code())))
                    return
                }
                val list = response.body() ?: emptyList()
                trackersCache = list
                callback(RepositoryResult.Success(list))
            }

            override fun onFailure(call: Call<List<Tracker>>, t: Throwable) {
                callback(RepositoryResult.Failure(AppError.Network))
            }
        })
    }

    fun getTrackerGeometryResult(
        context: Context,
        id: String,
        callback: (RepositoryResult<Tracker>) -> Unit
    ) {
        val key = TrackDataCacheKey(id)
        val cached = geometryCache[key]
        if (cached != null) {
            callback(RepositoryResult.Success(cached))
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(RepositoryResult.Failure(AppError.MissingServerUrl))
            return
        }
        val api = createApi(context, serverUrl)
        api.getTrackerGeometry(id).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                if (!response.isSuccessful) {
                    callback(RepositoryResult.Failure(errorFromResponseCode(response.code())))
                    return
                }
                val tracker = response.body()
                if (tracker == null) {
                    callback(RepositoryResult.Failure(AppError.Unknown))
                    return
                }
                geometryCache[key] = tracker
                callback(RepositoryResult.Success(tracker))
            }

            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                callback(RepositoryResult.Failure(AppError.Network))
            }
        })
    }

    fun updateTrackerSettingsResult(
        context: Context,
        id: String,
        request: TrackerSettingsRequest,
        callback: (RepositoryResult<Tracker>) -> Unit
    ) {
        updateTrackerSettings(context, id, request) { tracker, errorMessage ->
            if (tracker != null) {
                callback(RepositoryResult.Success(tracker))
                return@updateTrackerSettings
            }
            if (!errorMessage.isNullOrBlank()) {
                callback(RepositoryResult.Failure(AppError.Validation(errorMessage)))
            } else {
                callback(RepositoryResult.Failure(AppError.Unknown))
            }
        }
    }

    fun getTrackers(context: Context, forceRefresh: Boolean = false, callback: (List<Tracker>?) -> Unit) {
        if (!forceRefresh && trackersCache != null) {
            callback(trackersCache)
            return
        }

        if (isFetching) {
            listeners.add(callback)
            return
        }

        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }

        isFetching = true
        listeners.add(callback)
        
        val api = createApi(context, serverUrl)

        api.getTrackers().enqueue(object : Callback<List<Tracker>> {
            override fun onResponse(call: Call<List<Tracker>>, response: Response<List<Tracker>>) {
                if (!response.isSuccessful) {
                    isFetching = false
                    notifyListeners(null)
                    return
                }
                val list = response.body() ?: emptyList()
                isFetching = false
                trackersCache = list
                notifyListeners(list)
            }

            override fun onFailure(call: Call<List<Tracker>>, t: Throwable) {
                isFetching = false
                Log.e("TrackerRepository", "Failed to fetch trackers", t)
                notifyListeners(null)
            }
        })
    }

    private fun notifyListeners(data: List<Tracker>?) {
        val currentListeners = listeners.toList()
        listeners.clear()
        currentListeners.forEach { it(data) }
    }
    
    fun setTrackers(trackers: List<Tracker>) {
        trackersCache = trackers
    }

    /** Inserts a newly created tracker into the cache at the correct sorted position (by name). No network call. */
    fun insertTrackerInCache(tracker: Tracker) {
        val current = trackersCache?.toMutableList() ?: mutableListOf()
        current.add(tracker)
        current.sortBy { it.name.lowercase() }
        trackersCache = current
    }

    /** Returns the full trackers list from cache if present; used to show Trackers tab without waiting for network. */
    fun getTrackersCache(): List<Tracker>? = trackersCache

    /** Returns the tracker from the list cache if present; used for "last updated" on first tap before geometry loads. */
    fun getTrackerFromCache(id: String): Tracker? = trackersCache?.find { it.id == id }

    /** Clears list-level caches (trackers, groups, map visibility). Use on logout or when list data may be stale. */
    fun clearListCaches() {
        trackersCache = null
        groupsCache = null
        mapVisibilityCache = null
    }

    fun getTracker(context: Context, id: String, forceRefresh: Boolean = false, callback: (Tracker?) -> Unit) {
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(context)
        if (!forceRefresh) {
            if (id == selectedId && id == currentTrackerId && currentTrackerCache != null) {
                callback(currentTrackerCache)
                return
            }
            val cached = trackersCache?.find { it.id == id }
            if (cached != null) {
                if (id == selectedId) {
                    currentTrackerId = id
                    currentTrackerCache = cached
                }
                callback(cached)
                return
            }
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        trackerCall?.cancel()
        val call = api.getTracker(id)
        trackerCall = call
        call.enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                trackerCall = null
                val tracker = if (response.isSuccessful) response.body() else null
                if (tracker != null && id == selectedId) {
                    currentTrackerId = id
                    currentTrackerCache = tracker
                }
                callback(tracker)
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                trackerCall = null
                if (!call.isCanceled()) Log.e("TrackerRepository", "Failed to fetch tracker", t)
                callback(null)
            }
        })
    }

    private var trackerCall: Call<Tracker>? = null

    /** Cancels any in-flight GET tracker request. Call when the edit screen is closed so the callback does not run. */
    fun cancelTrackerRequest() {
        trackerCall?.cancel()
        trackerCall = null
    }

    private var geometryCall: Call<Tracker>? = null
    private var coordinatesCall: Call<TrackerCoordinatesResponse>? = null
    /** Cached geometry keyed by tracker and all-data mode. */
    private val geometryCache = mutableMapOf<TrackDataCacheKey, Tracker>()
    /** Cached coordinates keyed by tracker and all-data mode. */
    private val coordinatesCache = mutableMapOf<TrackDataCacheKey, TrackerCoordinatesResponse>()

    /**
     * Cancels any in-flight full geometry request. Call when the map reset is tapped so the
     * previous request does not overwrite the track after switching back to the default track.
     */
    fun cancelGeometryRequest() {
        geometryCall?.cancel()
        geometryCall = null
    }

    /** Clears only geometry and coordinates caches. Use when geometry is invalidated but selection unchanged (e.g. clear tracker history). */
    fun clearGeometryCache() {
        geometryCache.clear()
        coordinatesCache.clear()
    }

    /** Returns cached filtered geometry for the provided tracker id without making a network request. */
    fun getTrackerGeometryFromCache(id: String): Tracker? {
        return geometryCache[TrackDataCacheKey(id)]
    }

    /** Returns cached filtered coordinates for the provided tracker id without making a network request. */
    fun getTrackerCoordinatesFromCache(id: String): TrackerCoordinatesResponse? {
        return coordinatesCache[TrackDataCacheKey(id)]
    }

    /**
     * Fetches full track geometry and point_params (for map, params table). Use this when geometry is needed.
     * Returns cached geometry when available for the requested tracker (e.g. after changing default track).
     */
    fun getTrackerGeometry(
        context: Context,
        id: String,
        callback: (Tracker?) -> Unit
    ) {
        val key = TrackDataCacheKey(id)
        val cached = geometryCache[key]
        if (cached != null) {
            callback(cached)
            return
        }
        refreshTrackerGeometry(context, id, callback)
    }

    /**
     * Force-refreshes geometry from network and updates geometry/coordinates caches.
     * Use this when startup should proactively refresh selected tracker history.
     */
    fun refreshTrackerGeometry(
        context: Context,
        id: String,
        callback: (Tracker?) -> Unit
    ) {
        cancelGeometryRequest()
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(context)
        val api = createApi(context, serverUrl)
        val call = api.getTrackerGeometry(id)
        geometryCall = call
        call.enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                geometryCall = null
                val tracker = if (response.isSuccessful) response.body() else null
                if (tracker != null && tracker.id == selectedId) {
                    geometryCache[TrackDataCacheKey(tracker.id)] = tracker
                    val coords = tracker.geometry?.coordinates
                    if (!coords.isNullOrEmpty()) {
                        coordinatesCache[TrackDataCacheKey(tracker.id)] = TrackerCoordinatesResponse(
                            coordinates = coords,
                            point_params = tracker.point_params
                        )
                    }
                }
                callback(tracker)
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                geometryCall = null
                if (call.isCanceled()) return
                Log.e("TrackerRepository", "Failed to fetch tracker geometry", t)
                callback(null)
            }
        })
    }

    /** Fetches tracker coordinates, optionally requesting all available points from backend. */
    fun getTrackerCoordinates(
        context: Context,
        id: String,
        callback: (TrackerCoordinatesResponse?) -> Unit
    ) {
        val key = TrackDataCacheKey(id)
        val cached = coordinatesCache[key]
        if (cached != null) {
            callback(cached)
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        coordinatesCall?.cancel()
        val call = api.getTrackerCoordinates(id)
        coordinatesCall = call
        call.enqueue(object : Callback<TrackerCoordinatesResponse> {
            override fun onResponse(call: Call<TrackerCoordinatesResponse>, response: Response<TrackerCoordinatesResponse>) {
                coordinatesCall = null
                val body = if (response.isSuccessful) response.body() else null
                if (body != null) {
                    coordinatesCache[key] = body
                }
                callback(body)
            }

            override fun onFailure(call: Call<TrackerCoordinatesResponse>, t: Throwable) {
                coordinatesCall = null
                if (call.isCanceled()) return
                Log.e("TrackerRepository", "Failed to fetch tracker coordinates", t)
                callback(null)
            }
        })
    }

    /** Fetch geometry for multiple trackers in one request, respecting tracker filtering settings. */
    fun getTrackersGeometry(
        context: Context,
        trackerIds: List<String>,
        callback: (List<Tracker>?) -> Unit
    ) {
        if (trackerIds.isEmpty()) {
            callback(emptyList())
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        val request = TrackerBulkGeometryRequest(tracker_ids = trackerIds)
        api.getTrackersGeometry(request).enqueue(object : Callback<List<Tracker>> {
            override fun onResponse(call: Call<List<Tracker>>, response: Response<List<Tracker>>) {
                callback(if (response.isSuccessful) (response.body() ?: emptyList()) else null)
            }

            override fun onFailure(call: Call<List<Tracker>>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to fetch trackers geometry", t)
                callback(null)
            }
        })
    }

    /** Clears all caches tied to the selected tracker (current tracker, geometry, coordinates). Use when selection changes or when switching to view another tracker. */
    fun clearSelectedTrackerCaches() {
        currentTrackerId = null
        currentTrackerCache = null
        geometryCache.clear()
        coordinatesCache.clear()
    }

    fun updateTrackerSettings(
        context: Context,
        id: String,
        name: String,
        color: String?,
        recentDataWindow: String?,
        callback: (Tracker?) -> Unit
    ) {
        updateTrackerSettings(context, id, TrackerSettingsRequest(
            name = name,
            color = color?.takeIf { it.isNotBlank() },
            recent_data_window = recentDataWindow?.takeIf { it.isNotBlank() }
        )) { tracker, _ -> callback(tracker) }
    }

    /**
     * POST trackers/<id>/settings/ with full sharing fields.
     * On 400 with invalid_emails, errorMessage is set (e.g. "Invalid emails: a@b.com, c@d.com").
     */
    fun updateTrackerSettings(
        context: Context,
        id: String,
        request: TrackerSettingsRequest,
        callback: (Tracker?, errorMessage: String?) -> Unit
    ) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null, null)
            return
        }
        val api = createApi(context, serverUrl)
        api.postTrackerSettings(id, request)
            .enqueue(object : Callback<Tracker> {
                override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                    if (response.isSuccessful) {
                        val tracker = response.body()
                        if (tracker != null) {
                            val selectedId = SelectedTrackerPrefs.selectedTrackerId(context)
                            if (id == selectedId) {
                                currentTrackerId = id
                                currentTrackerCache = tracker
                            }
                            trackersCache = null
                            clearGeometryCache()
                        }
                        callback(tracker, null)
                        return
                    }
                    val errorMsg = response.errorBody()?.string()?.let { body ->
                        try {
                            val json = org.json.JSONObject(body)
                            val invalid = json.optJSONArray("invalid_emails")
                            if (invalid != null && invalid.length() > 0) {
                                val list = (0 until invalid.length()).map { invalid.getString(it) }
                                "Invalid emails: ${list.joinToString(", ")}"
                            } else {
                                json.optString("error", body.take(200))
                            }
                        } catch (e: Exception) {
                            Log.w("TrackerRepository", "Could not parse tracker settings error response", e)
                            body.take(200)
                        }
                    }
                    callback(null, errorMsg)
                }
                override fun onFailure(call: Call<Tracker>, t: Throwable) {
                    Log.e("TrackerRepository", "Failed to update tracker settings", t)
                    callback(null, null)
                }
            })
    }

    fun deleteTracker(context: Context, id: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.deleteTracker(id).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    trackersCache = trackersCache?.filterNot { it.id == id }
                    geometryCache.entries.removeAll { it.key.trackerId == id }
                    coordinatesCache.entries.removeAll { it.key.trackerId == id }
                    if (id == currentTrackerId) {
                        currentTrackerId = null
                        currentTrackerCache = null
                    }
                }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to delete tracker", t)
                callback(false)
            }
        })
    }

    /**
     * Efficient single-tracker check (POST tracker-check). Use to validate the selected tracker
     * before starting a session; call back with true only if the tracker exists and belongs to the user.
     */
    fun checkTracker(context: Context, trackerId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.checkTracker(TrackerCheckRequest(tracker_id = trackerId, password = null))
            .enqueue(object : Callback<TrackerCheckResponse> {
                override fun onResponse(
                    call: Call<TrackerCheckResponse>,
                    response: Response<TrackerCheckResponse>
                ) {
                    val body = response.body()
                    callback(body?.valid == true)
                }

                override fun onFailure(call: Call<TrackerCheckResponse>, t: Throwable) {
                    Log.e("TrackerRepository", "Tracker check failed", t)
                    callback(false)
                }
            })
    }

    private var availableToAddCache: AvailableToAddResponse? = null

    fun getAvailableToAdd(context: Context, forceRefresh: Boolean = false, callback: (AvailableToAddResponse?) -> Unit) {
        if (!forceRefresh && availableToAddCache != null) {
            callback(availableToAddCache)
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getAvailableToAdd().enqueue(object : Callback<AvailableToAddResponse> {
            override fun onResponse(call: Call<AvailableToAddResponse>, response: Response<AvailableToAddResponse>) {
                val body = response.body()
                if (body != null) availableToAddCache = body
                callback(body)
            }
            override fun onFailure(call: Call<AvailableToAddResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to get available-to-add", t)
                callback(null)
            }
        })
    }

    fun prefetchAvailableToAdd(context: Context) {
        getAvailableToAdd(context, forceRefresh = true) { }
    }

    fun subscribeTracker(context: Context, trackerId: String, callback: (Tracker?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.subscribeTracker(trackerId).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                if (response.isSuccessful) {
                    trackersCache = null
                    availableToAddCache = null
                }
                callback(response.body())
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                Log.e("TrackerRepository", "Subscribe failed", t)
                callback(null)
            }
        })
    }

    fun unsubscribeTracker(context: Context, trackerId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.unsubscribeTracker(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    trackersCache = trackersCache?.filterNot { it.id == trackerId }
                    availableToAddCache = null
                }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Unsubscribe failed", t)
                callback(false)
            }
        })
    }

    fun leaveShareWithMe(context: Context, trackerId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.leaveShareWithMe(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    trackersCache = trackersCache?.filterNot { it.id == trackerId }
                    availableToAddCache = null
                }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Leave share failed", t)
                callback(false)
            }
        })
    }

    fun getSubscribers(context: Context, trackerId: String, callback: (SubscribersResponse?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getSubscribers(trackerId).enqueue(object : Callback<SubscribersResponse> {
            override fun onResponse(call: Call<SubscribersResponse>, response: Response<SubscribersResponse>) {
                callback(response.body())
            }
            override fun onFailure(call: Call<SubscribersResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Get subscribers failed", t)
                callback(null)
            }
        })
    }

    fun getMapVisibility(context: Context, callback: (MapVisibilityResponse?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getMapVisibility().enqueue(object : Callback<MapVisibilityResponse> {
            override fun onResponse(call: Call<MapVisibilityResponse>, response: Response<MapVisibilityResponse>) {
                val body = response.body()
                if (body != null) mapVisibilityCache = body
                callback(body)
            }
            override fun onFailure(call: Call<MapVisibilityResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Get map visibility failed", t)
                callback(null)
            }
        })
    }

    fun patchMapVisibility(
        context: Context,
        request: MapVisibilityRequest,
        callback: (MapVisibilityResponse?) -> Unit
    ) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.patchMapVisibility(request).enqueue(object : Callback<MapVisibilityResponse> {
            override fun onResponse(call: Call<MapVisibilityResponse>, response: Response<MapVisibilityResponse>) {
                val body = response.body()
                if (body != null) mapVisibilityCache = body
                callback(body)
            }
            override fun onFailure(call: Call<MapVisibilityResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Patch map visibility failed", t)
                callback(null)
            }
        })
    }

    @Volatile
    private var groupsCache: List<Group>? = null

    fun getGroupsCache(): List<Group>? = groupsCache

    /** Inserts a newly created group into the cache at the correct sorted position (by name). No network call. */
    fun insertGroupInCache(group: Group) {
        val current = groupsCache?.toMutableList() ?: mutableListOf()
        current.add(group)
        current.sortBy { it.name.lowercase() }
        groupsCache = current
    }

    fun getGroups(context: Context, forceRefresh: Boolean = false, callback: (List<Group>?) -> Unit) {
        if (!forceRefresh && groupsCache != null) {
            callback(groupsCache)
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getGroups().enqueue(object : Callback<List<Group>> {
            override fun onResponse(call: Call<List<Group>>, response: Response<List<Group>>) {
                val list = response.body() ?: emptyList()
                groupsCache = list
                callback(list)
            }
            override fun onFailure(call: Call<List<Group>>, t: Throwable) {
                Log.e("TrackerRepository", "Get groups failed", t)
                callback(null)
            }
        })
    }

    /** Prefetch groups into cache so Groups screen opens instantly. Call when Trackers tab is visible. */
    fun prefetchGroups(context: Context) {
        getGroups(context, forceRefresh = true) { }
    }

    /** Prefetch data used by the Shared tab so it opens instantly. Call on app launch. */
    fun prefetchSharedPage(context: Context) {
        getMapVisibility(context) { }
        getGroups(context, forceRefresh = true) { }
        getTrackers(context, forceRefresh = true) { }
    }

    fun createGroup(context: Context, name: String, callback: (Group?, errorMessage: String?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null, null)
            return
        }
        val api = createApi(context, serverUrl)
        api.createGroup(GroupCreateRequest(name = name)).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                if (response.isSuccessful) {
                    response.body()?.let { insertGroupInCache(it) }
                    callback(response.body(), null)
                    return
                }
                val errorMsg = response.errorBody()?.string()?.let { body ->
                    try {
                        val json = org.json.JSONObject(body)
                        json.optString("detail", json.optString("name", body.take(200)))
                    } catch (e: Exception) {
                        Log.w("TrackerRepository", "Could not parse create group error response", e)
                        body.take(200)
                    }
                }
                callback(null, errorMsg?.takeIf { it.isNotBlank() } ?: "Failed to create group")
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Create group failed", t)
                callback(null, null)
            }
        })
    }

    fun getGroup(context: Context, groupId: String, callback: (Group?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getGroup(groupId).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                callback(response.body())
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Get group failed", t)
                callback(null)
            }
        })
    }

    fun patchGroup(context: Context, groupId: String, request: GroupPatchRequest, callback: (Group?, errorMessage: String?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null, null)
            return
        }
        val api = createApi(context, serverUrl)
        api.patchGroup(groupId, request).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                if (response.isSuccessful) {
                    groupsCache = null
                    callback(response.body(), null)
                    return
                }
                val errorMsg = response.errorBody()?.string()?.let { body ->
                    try {
                        val json = org.json.JSONObject(body)
                        json.optString("detail", json.optString("name", body.take(200)))
                    } catch (e: Exception) {
                        Log.w("TrackerRepository", "Could not parse patch group error response", e)
                        body.take(200)
                    }
                }
                callback(null, errorMsg?.takeIf { it.isNotBlank() } ?: "Failed to save group")
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Patch group failed", t)
                callback(null, null)
            }
        })
    }

    fun deleteGroup(context: Context, groupId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.deleteGroup(groupId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) groupsCache = groupsCache?.filterNot { it.id == groupId }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Delete group failed", t)
                callback(false)
            }
        })
    }

    fun addGroupTrack(context: Context, groupId: String, trackId: String, callback: (Group?, errorMessage: String?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null, null)
            return
        }
        val api = createApi(context, serverUrl)
        api.addGroupTrack(groupId, GroupAddTrackRequest(track_id = trackId)).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                if (response.isSuccessful) {
                    groupsCache = null
                    callback(response.body(), null)
                    return
                }
                val errorMsg = response.errorBody()?.string()?.let { body ->
                    try {
                        val json = org.json.JSONObject(body)
                        json.optString("detail", json.optString("error", body.take(200)))
                    } catch (e: Exception) {
                        Log.w("TrackerRepository", "Could not parse add group track error response", e)
                        body.take(200)
                    }
                }
                callback(null, errorMsg?.takeIf { it.isNotBlank() } ?: "Failed to add tracker")
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Add group track failed", t)
                callback(null, null)
            }
        })
    }

    fun removeGroupTrack(context: Context, groupId: String, trackId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.removeGroupTrack(groupId, trackId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) groupsCache = null
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Remove group track failed", t)
                callback(false)
            }
        })
    }

    fun acceptGroupShare(context: Context, groupId: String, callback: (Group?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.acceptGroupShare(groupId).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                if (response.isSuccessful) {
                    groupsCache = null
                    trackersCache = null
                    availableToAddCache = null
                }
                callback(response.body())
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Accept group share failed", t)
                callback(null)
            }
        })
    }

    fun leaveGroup(context: Context, groupId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val api = createApi(context, serverUrl)
        api.leaveGroup(groupId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    groupsCache = groupsCache?.filterNot { it.id == groupId }
                    availableToAddCache = null
                }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Leave group failed", t)
                callback(false)
            }
        })
    }

    fun getUsers(context: Context, callback: (UsersResponse?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getUsers().enqueue(object : Callback<UsersResponse> {
            override fun onResponse(call: Call<UsersResponse>, response: Response<UsersResponse>) {
                callback(response.body())
            }
            override fun onFailure(call: Call<UsersResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Get users failed", t)
                callback(null)
            }
        })
    }

    /** Returns the URL to download KML for the tracker (authenticated GET). */
    fun getTrackerKmlUrl(context: Context, trackerId: String): String {
        val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        return "$serverUrl/api/extensions/live-track/trackers/$trackerId/kml/"
    }

    /** Fetches KML response body for the tracker (for download/share). */
    fun fetchTrackerKml(
        context: Context,
        trackerId: String,
        callback: (ResponseBody?) -> Unit
    ) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val api = createApi(context, serverUrl)
        api.getTrackerKml(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                callback(if (response.isSuccessful) response.body() else null)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Fetch KML failed", t)
                callback(null)
            }
        })
    }

    suspend fun getTrackersSuspend(context: Context, forceRefresh: Boolean = false): List<Tracker>? =
        suspendCancellableCoroutine { continuation ->
            getTrackers(context, forceRefresh = forceRefresh) { list ->
                continuation.resume(list)
            }
        }

    suspend fun getAvailableToAddSuspend(context: Context, forceRefresh: Boolean = false): AvailableToAddResponse? =
        suspendCancellableCoroutine { continuation ->
            getAvailableToAdd(context, forceRefresh = forceRefresh) { response ->
                continuation.resume(response)
            }
        }

    suspend fun getMapVisibilitySuspend(context: Context): MapVisibilityResponse? =
        suspendCancellableCoroutine { continuation ->
            getMapVisibility(context) { visibility ->
                continuation.resume(visibility)
            }
        }

    suspend fun getGroupsSuspend(context: Context, forceRefresh: Boolean = false): List<Group>? =
        suspendCancellableCoroutine { continuation ->
            getGroups(context, forceRefresh = forceRefresh) { groups ->
                continuation.resume(groups)
            }
        }

    suspend fun getGroupSuspend(context: Context, id: String): Group? =
        suspendCancellableCoroutine { continuation ->
            getGroup(context, id) { group ->
                continuation.resume(group)
            }
        }

    suspend fun patchGroupSuspend(context: Context, id: String, request: GroupPatchRequest): Group? =
        suspendCancellableCoroutine { continuation ->
            patchGroup(context, id, request) { group, _ ->
                continuation.resume(group)
            }
        }

    suspend fun updateTrackerSettingsSuspend(
        context: Context,
        id: String,
        request: TrackerSettingsRequest
    ): Tracker? = suspendCancellableCoroutine { continuation ->
        updateTrackerSettings(context, id, request) { tracker, _ ->
            continuation.resume(tracker)
        }
    }

    suspend fun checkTrackerSuspend(context: Context, trackerId: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            checkTracker(context, trackerId) { valid ->
                continuation.resume(valid)
            }
        }

    suspend fun getUsersSuspend(context: Context): UsersResponse? =
        suspendCancellableCoroutine { continuation ->
            getUsers(context) { users ->
                continuation.resume(users)
            }
        }

    suspend fun getTrackerSuspend(context: Context, id: String, forceRefresh: Boolean = false): Tracker? =
        suspendCancellableCoroutine { continuation ->
            getTracker(context, id, forceRefresh = forceRefresh) { tracker ->
                continuation.resume(tracker)
            }
        }

    suspend fun getTrackerGeometrySuspend(
        context: Context,
        id: String
    ): Tracker? =
        suspendCancellableCoroutine { continuation ->
            getTrackerGeometry(context, id) { tracker ->
                continuation.resume(tracker)
            }
        }

    suspend fun refreshTrackerGeometrySuspend(
        context: Context,
        id: String
    ): Tracker? =
        suspendCancellableCoroutine { continuation ->
            refreshTrackerGeometry(context, id) { tracker ->
                continuation.resume(tracker)
            }
        }

    suspend fun updateTrackerSettingsResultSuspend(
        context: Context,
        id: String,
        request: TrackerSettingsRequest
    ): RepositoryResult<Tracker> = suspendCancellableCoroutine { continuation ->
        updateTrackerSettingsResult(context, id, request) { result ->
            continuation.resume(result)
        }
    }

    suspend fun deleteTrackerSuspend(context: Context, id: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            deleteTracker(context, id) { success ->
                continuation.resume(success)
            }
        }

    suspend fun deleteTrackerResultSuspend(context: Context, id: String): RepositoryResult<Unit> =
        boolResult(deleteTrackerSuspend(context, id))

    suspend fun fetchTrackerKmlSuspend(context: Context, trackerId: String): ResponseBody? =
        suspendCancellableCoroutine { continuation ->
            fetchTrackerKml(context, trackerId) { body ->
                continuation.resume(body)
            }
        }

    suspend fun acceptGroupShareSuspend(context: Context, groupId: String): Group? =
        suspendCancellableCoroutine { continuation ->
            acceptGroupShare(context, groupId) { accepted ->
                continuation.resume(accepted)
            }
        }

    suspend fun subscribeTrackerSuspend(context: Context, trackerId: String): Tracker? =
        suspendCancellableCoroutine { continuation ->
            subscribeTracker(context, trackerId) { tracker ->
                continuation.resume(tracker)
            }
        }

    suspend fun unsubscribeTrackerSuspend(context: Context, trackerId: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            unsubscribeTracker(context, trackerId) { success ->
                continuation.resume(success)
            }
        }

    suspend fun unsubscribeTrackerResultSuspend(
        context: Context,
        trackerId: String
    ): RepositoryResult<Unit> = boolResult(unsubscribeTrackerSuspend(context, trackerId))

    suspend fun leaveGroupSuspend(context: Context, groupId: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            leaveGroup(context, groupId) { success ->
                continuation.resume(success)
            }
        }

    suspend fun leaveGroupResultSuspend(context: Context, groupId: String): RepositoryResult<Unit> =
        boolResult(leaveGroupSuspend(context, groupId))

    suspend fun patchGroupResultSuspend(
        context: Context,
        id: String,
        request: GroupPatchRequest
    ): Pair<Group?, String?> = suspendCancellableCoroutine { continuation ->
        patchGroup(context, id, request) { group, error ->
            continuation.resume(Pair(group, error))
        }
    }

    suspend fun patchMapVisibilitySuspend(
        context: Context,
        request: MapVisibilityRequest
    ): MapVisibilityResponse? = suspendCancellableCoroutine { continuation ->
        patchMapVisibility(context, request) { response ->
            continuation.resume(response)
        }
    }

    suspend fun deleteGroupSuspend(context: Context, id: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            deleteGroup(context, id) { success ->
                continuation.resume(success)
            }
        }

    suspend fun deleteGroupResultSuspend(context: Context, id: String): RepositoryResult<Unit> =
        boolResult(deleteGroupSuspend(context, id))
}

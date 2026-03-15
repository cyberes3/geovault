package com.geovault.tracker

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object TrackerRepository {
    private var trackersCache: List<Tracker>? = null
    private var isFetching = false
    private val listeners = mutableListOf<(List<Tracker>?) -> Unit>()

    /** Cached map visibility; updated by getMapVisibility and patchMapVisibility. */
    @Volatile
    private var mapVisibilityCache: MapVisibilityResponse? = null

    fun getMapVisibilityCache(): MapVisibilityResponse? = mapVisibilityCache

    private var currentTrackerCache: Tracker? = null
    private var currentTrackerId: String? = null

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
        
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)

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

    fun clearCache() {
        trackersCache = null
        groupsCache = null
        mapVisibilityCache = null
    }

    fun getTracker(context: Context, id: String, forceRefresh: Boolean = false, callback: (Tracker?) -> Unit) {
        val defaultId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        if (!forceRefresh) {
            if (id == defaultId && id == currentTrackerId && currentTrackerCache != null) {
                callback(currentTrackerCache)
                return
            }
            val cached = trackersCache?.find { it.id == id }
            if (cached != null) {
                if (id == defaultId) {
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        trackerCall?.cancel()
        val call = api.getTracker(id)
        trackerCall = call
        call.enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                trackerCall = null
                val tracker = if (response.isSuccessful) response.body() else null
                if (tracker != null && id == defaultId) {
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
    /** Cached full geometry for the selected tracker; used when default track is changed so the map can show full track without a second fetch. */
    private var geometryCache: Pair<String, Tracker>? = null

    /**
     * Cancels any in-flight full geometry request. Call when the map reset is tapped so the
     * previous request does not overwrite the track after switching back to the default track.
     */
    fun cancelGeometryRequest() {
        geometryCall?.cancel()
        geometryCall = null
    }

    /**
     * Clears the cached full geometry. Call when the default track is unset so stale data is not shown.
     */
    fun clearGeometryCache() {
        geometryCache = null
    }

    /**
     * Fetches full track geometry and point_params (for map, params table). Use this when geometry is needed.
     * Returns cached geometry when available for the requested tracker (e.g. after changing default track).
     */
    fun getTrackerGeometry(context: Context, id: String, callback: (Tracker?) -> Unit) {
        val cached = geometryCache
        if (cached != null && cached.first == id) {
            callback(cached.second)
            return
        }
        cancelGeometryRequest()
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val selectedId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        val call = api.getTrackerGeometry(id)
        geometryCall = call
        call.enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                geometryCall = null
                val tracker = if (response.isSuccessful) response.body() else null
                if (tracker != null && tracker.id == selectedId) {
                    geometryCache = tracker.id to tracker
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

    /** Fetches latest coordinates (up to server limit, usually 100) without canceling other requests. */
    fun getTrackerCoordinates(context: Context, id: String, callback: (TrackerCoordinatesResponse?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.getTrackerCoordinates(id).enqueue(object : Callback<TrackerCoordinatesResponse> {
            override fun onResponse(call: Call<TrackerCoordinatesResponse>, response: Response<TrackerCoordinatesResponse>) {
                callback(if (response.isSuccessful) response.body() else null)
            }

            override fun onFailure(call: Call<TrackerCoordinatesResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to fetch tracker coordinates", t)
                callback(null)
            }
        })
    }

    fun clearCurrentTrackerCache() {
        currentTrackerId = null
        currentTrackerCache = null
        geometryCache = null
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.postTrackerSettings(id, request)
            .enqueue(object : Callback<Tracker> {
                override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                    if (response.isSuccessful) {
                        val tracker = response.body()
                        if (tracker != null) {
                            val defaultId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                                .getString("selected_tracker_id", "") ?: ""
                            if (id == defaultId) {
                                currentTrackerId = id
                                currentTrackerCache = tracker
                            }
                            trackersCache = null
                            geometryCache = null
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
                        } catch (_: Exception) {
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.deleteTracker(id).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    trackersCache = null
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

    fun clearTrackerHistory(context: Context, trackerId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.clearTrackerHistory(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    if (geometryCache?.first == trackerId) {
                        geometryCache = null
                    }
                    clearGeometryCache()
                    trackersCache = null
                }
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to clear tracker history", t)
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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

    fun getAvailableToAdd(context: Context, callback: (AvailableToAddResponse?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.getAvailableToAdd().enqueue(object : Callback<AvailableToAddResponse> {
            override fun onResponse(call: Call<AvailableToAddResponse>, response: Response<AvailableToAddResponse>) {
                callback(response.body())
            }
            override fun onFailure(call: Call<AvailableToAddResponse>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to get available-to-add", t)
                callback(null)
            }
        })
    }

    fun subscribeTracker(context: Context, trackerId: String, callback: (Tracker?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.subscribeTracker(trackerId).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                if (response.isSuccessful) {
                    trackersCache = null
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.unsubscribeTracker(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) trackersCache = null
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.leaveShareWithMe(trackerId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) trackersCache = null
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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

    fun createGroup(context: Context, name: String, callback: (Group?, errorMessage: String?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null, null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.createGroup(GroupCreateRequest(name = name)).enqueue(object : Callback<Group> {
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
                    } catch (_: Exception) {
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
                    } catch (_: Exception) {
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.deleteGroup(groupId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) groupsCache = null
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e("TrackerRepository", "Delete group failed", t)
                callback(false)
            }
        })
    }

    fun addGroupTrack(context: Context, groupId: String, trackId: String, callback: (Group?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.addGroupTrack(groupId, GroupAddTrackRequest(track_id = trackId)).enqueue(object : Callback<Group> {
            override fun onResponse(call: Call<Group>, response: Response<Group>) {
                groupsCache = null
                callback(response.body())
            }
            override fun onFailure(call: Call<Group>, t: Throwable) {
                Log.e("TrackerRepository", "Add group track failed", t)
                callback(null)
            }
        })
    }

    fun removeGroupTrack(context: Context, groupId: String, trackId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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

    fun leaveGroup(context: Context, groupId: String, callback: (Boolean) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(false)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.leaveGroup(groupId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) groupsCache = null
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
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
    fun fetchTrackerKml(context: Context, trackerId: String, callback: (ResponseBody?) -> Unit) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
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
}

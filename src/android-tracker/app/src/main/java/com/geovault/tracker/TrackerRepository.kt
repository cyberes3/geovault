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
    }

    fun getTracker(context: Context, id: String, callback: (Tracker?) -> Unit) {
        val defaultId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
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
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.getTracker(id).enqueue(object : Callback<Tracker> {
            override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                val tracker = if (response.isSuccessful) response.body() else null
                if (tracker != null && id == defaultId) {
                    currentTrackerId = id
                    currentTrackerCache = tracker
                }
                callback(tracker)
            }
            override fun onFailure(call: Call<Tracker>, t: Throwable) {
                Log.e("TrackerRepository", "Failed to fetch tracker", t)
                callback(null)
            }
        })
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

    fun clearCurrentTrackerCache() {
        currentTrackerId = null
        currentTrackerCache = null
        geometryCache = null
    }

    fun updateTracker(
        context: Context,
        id: String,
        name: String?,
        color: String?,
        callback: (Tracker?) -> Unit
    ) {
        val serverUrl = GeovaultAuthManager.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            callback(null)
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(context, baseUrl).create(TrackerApi::class.java)
        api.updateTracker(id, TrackerUpdateRequest(name = name, color = color))
            .enqueue(object : Callback<Tracker> {
                override fun onResponse(call: Call<Tracker>, response: Response<Tracker>) {
                    val tracker = if (response.isSuccessful) response.body() else null
                    if (tracker != null) {
                        val defaultId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                            .getString("selected_tracker_id", "") ?: ""
                        if (id == defaultId) {
                            currentTrackerId = id
                            currentTrackerCache = tracker
                        }
                        trackersCache = null
                    }
                    callback(tracker)
                }
                override fun onFailure(call: Call<Tracker>, t: Throwable) {
                    Log.e("TrackerRepository", "Failed to update tracker", t)
                    callback(null)
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
}

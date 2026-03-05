package com.geovault.tracker

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
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
                isFetching = false
                if (response.isSuccessful) {
                    trackersCache = response.body()
                    notifyListeners(trackersCache)
                } else {
                    notifyListeners(null)
                }
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
        if (id == currentTrackerId && currentTrackerCache != null) {
            callback(currentTrackerCache)
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
                if (tracker != null) {
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

    fun clearCurrentTrackerCache() {
        currentTrackerId = null
        currentTrackerCache = null
    }
}

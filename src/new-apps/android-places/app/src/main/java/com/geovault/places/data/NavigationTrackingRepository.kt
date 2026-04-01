package com.geovault.places.data

import android.content.Context
import com.geovault.places.model.Feature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class NavigationTrackingRepository(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val intListType = TypeToken.getParameterized(List::class.java, Int::class.javaObjectType).type

    fun buildMapsSearchUrl(feature: Feature): String? {
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return null
        val lon = coords[0]
        val lat = coords[1]
        val latString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", lat)
        val lonString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", lon)
        val encoded = URLEncoder.encode("$latString,$lonString", StandardCharsets.UTF_8.toString())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    fun trackNavigation(feature: Feature, serverUrl: String) {
        val dbId = feature.properties.database_id ?: return
        if (serverUrl.isBlank()) return
        val api = PlacesApiFactory.create(context, serverUrl)
        flushPending(serverUrl)
        api.trackNavigation(dbId).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) = Unit
            override fun onFailure(call: Call<Void>, t: Throwable) {
                addPending(dbId)
            }
        })
    }

    fun flushPending(serverUrl: String) {
        if (serverUrl.isBlank()) return
        val api = PlacesApiFactory.create(context, serverUrl)
        getPending().forEach { id ->
            api.trackNavigation(id).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) removePending(id)
                }
                override fun onFailure(call: Call<Void>, t: Throwable) = Unit
            })
        }
    }

    fun clearPending() {
        prefs.edit().remove(PENDING_NAVIGATION_IDS_KEY).apply()
    }

    private fun getPending(): List<Int> {
        val json = prefs.getString(PENDING_NAVIGATION_IDS_KEY, "[]") ?: "[]"
        return runCatching { gson.fromJson<List<Int>>(json, intListType) ?: emptyList() }.getOrElse { emptyList() }
    }

    private fun addPending(id: Int) {
        val pending = getPending().toMutableList()
        if (id !in pending) {
            pending.add(id)
            prefs.edit().putString(PENDING_NAVIGATION_IDS_KEY, gson.toJson(pending)).apply()
        }
    }

    private fun removePending(id: Int) {
        val pending = getPending().toMutableList()
        pending.remove(id)
        prefs.edit().putString(PENDING_NAVIGATION_IDS_KEY, gson.toJson(pending)).apply()
    }

    companion object {
        private const val PREFS_NAME = "geovault_prefs"
        private const val PENDING_NAVIGATION_IDS_KEY = "pending_navigation_ids"
        private const val COORDINATE_PRECISION_DP = 8
    }
}

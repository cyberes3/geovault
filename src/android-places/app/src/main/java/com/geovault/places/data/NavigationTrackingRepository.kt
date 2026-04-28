package com.geovault.places.data

import android.content.Context
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey
import com.geovault.places.domain.NavigationRetryFlusher
import com.geovault.places.model.Feature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class NavigationTrackingRepository(private val context: Context) : NavigationRetryFlusher {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS
    )
    private val gson = Gson()
    private val intListType = TypeToken.getParameterized(List::class.java, Int::class.javaObjectType).type
    private val pendingLock = Any()

    fun preloadOnLaunch() {
        store.preloadAllDataBlocking()
    }

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
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (!response.isSuccessful) addPending(dbId)
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                addPending(dbId)
            }
        })
    }

    override fun flushPending(serverUrl: String) {
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
        synchronized(pendingLock) {
            store.removeBlocking(KEY_PENDING_NAVIGATION_IDS)
        }
    }

    private fun getPending(): List<Int> {
        return synchronized(pendingLock) {
            readPendingLocked()
        }
    }

    private fun addPending(id: Int) {
        synchronized(pendingLock) {
            val pending = readPendingLocked().toMutableList()
            if (id !in pending) {
                pending.add(id)
                store.putBlocking(KEY_PENDING_NAVIGATION_IDS, gson.toJson(pending))
            }
        }
    }

    private fun removePending(id: Int) {
        synchronized(pendingLock) {
            val pending = readPendingLocked().toMutableList()
            pending.remove(id)
            store.putBlocking(KEY_PENDING_NAVIGATION_IDS, gson.toJson(pending))
        }
    }

    private fun readPendingLocked(): List<Int> {
        val json = store.getBlocking(KEY_PENDING_NAVIGATION_IDS)
        return runCatching { gson.fromJson<List<Int>>(json, intListType) ?: emptyList() }.getOrElse { emptyList() }
    }

    companion object {
        private const val PREFS_NAME = "geovault_places_nav"
        private const val SCHEMA_VERSION = 1
        private val KEY_PENDING_NAVIGATION_IDS = PrefKey.StringKey("pending_navigation_ids", "[]")
        private val ALL_KEYS: Set<PrefKey<*>> = setOf(KEY_PENDING_NAVIGATION_IDS)
        private const val COORDINATE_PRECISION_DP = 8
    }
}

package com.geovault.places

import android.content.Context
import com.geovault.common.RetrofitClient
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object NavigationHelper {

    private const val PREFS_NAME = "geovault_prefs"
    private const val PENDING_NAVIGATION_IDS_KEY = "pending_navigation_ids"
    private val gson = Gson()
    private val intListType = object : TypeToken<List<Int>>() {}.type

    fun navigateToPlace(context: Context, feature: Feature, serverUrl: String) {
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            val lon = coords[0]
            val lat = coords[1]
            val label = feature.properties.name ?: "Place"

            // 1. Launch Map Intent
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)

            // 2. Notify backend of navigation (and flush any pending)
            val databaseId = feature.properties.database_id
            if (databaseId != null && serverUrl.isNotEmpty()) {
                val appContext = context.applicationContext
                val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                val api = RetrofitClient.getClient(appContext, baseUrl).create(GeovaultApi::class.java)

                flushPendingNavigations(appContext, api)
                api.trackNavigation(databaseId).enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        // Successfully notified
                    }
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        addPendingNavigationId(appContext, databaseId)
                    }
                })
            }
        }
    }

    fun flushPendingNavigations(context: Context, serverUrl: String) {
        val appContext = context.applicationContext
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(appContext, baseUrl).create(GeovaultApi::class.java)
        flushPendingNavigations(appContext, api)
    }

    private fun flushPendingNavigations(
        context: Context,
        api: GeovaultApi
    ) {
        val pendingIds = getPendingNavigationIds(context)
        for (id in pendingIds) {
            api.trackNavigation(id).enqueue(object : Callback<Void> {
                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    if (response.isSuccessful) {
                        removePendingNavigationId(context, id)
                    }
                }
                override fun onFailure(call: Call<Void>, t: Throwable) {
                    // Leave in list; will retry next time
                }
            })
        }
    }

    private fun getPendingNavigationIds(context: Context): List<Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PENDING_NAVIGATION_IDS_KEY, "[]") ?: "[]"
        return try {
            gson.fromJson(json, intListType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addPendingNavigationId(context: Context, id: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = getPendingNavigationIds(context).toMutableList()
        if (id !in pending) {
            pending.add(id)
            prefs.edit().putString(PENDING_NAVIGATION_IDS_KEY, gson.toJson(pending)).apply()
        }
    }

    private fun removePendingNavigationId(context: Context, id: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = getPendingNavigationIds(context).toMutableList()
        pending.remove(id)
        prefs.edit().putString(PENDING_NAVIGATION_IDS_KEY, gson.toJson(pending)).apply()
    }
}

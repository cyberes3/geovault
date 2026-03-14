package com.geovault.places

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import com.geovault.common.RetrofitClient
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object NavigationHelper {

    private const val PREFS_NAME = "geovault_prefs"
    private const val PENDING_NAVIGATION_IDS_KEY = "pending_navigation_ids"
    private val gson = Gson()
    // Use getParameterized so R8 doesn't strip generic signature (anonymous TypeToken fails with ProGuard)
    private val intListType = TypeToken.getParameterized(List::class.java, Int::class.javaObjectType).type
    private const val COORDINATE_PRECISION_DP = 8

    fun navigateToPlace(context: Context, feature: Feature, serverUrl: String) {
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            val lon = coords[0]
            val lat = coords[1]

            // 1. Launch Maps URL intent (catch when no app can handle it)
            val uri = buildMapsSearchUri(lat = lat, lon = lon)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val message = context.getString(R.string.no_map_app)
                val activity = context as? Activity
                when (activity) {
                    is MainActivity -> activity.showSnackbar(message)
                    else -> {
                        val rootView = activity?.findViewById<android.view.View>(android.R.id.content)
                        if (rootView != null) {
                            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

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

    internal fun buildMapsSearchUri(lat: Double, lon: Double): Uri {
        return Uri.parse(buildMapsSearchUrl(lat = lat, lon = lon))
    }

    internal fun buildMapsSearchUrl(lat: Double, lon: Double): String {
        val latString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", lat)
        val lonString = String.format(Locale.US, "%.${COORDINATE_PRECISION_DP}f", lon)
        val coordinateQuery = "$latString,$lonString"
        val encodedCoordinateQuery = URLEncoder.encode(coordinateQuery, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/maps/search/?api=1&query=$encodedCoordinateQuery"
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

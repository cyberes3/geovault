package com.geovault.places

import android.content.Context
import android.content.Intent
import android.net.Uri
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object NavigationHelper {

    fun navigateToPlace(context: Context, feature: Feature, apiKey: String, serverUrl: String) {
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            val lon = coords[0]
            val lat = coords[1]
            val label = feature.properties.name ?: "Place"
            
            // 1. Launch Map Intent
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)

            // 2. Track Navigation on Backend
            val databaseId = feature.properties.database_id
            if (databaseId != null && serverUrl.isNotEmpty()) {
                val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)
                
                api.trackNavigation(databaseId).enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        // Successfully tracked
                    }
                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        // Silent failure for tracking
                    }
                })
            }
        }
    }
}

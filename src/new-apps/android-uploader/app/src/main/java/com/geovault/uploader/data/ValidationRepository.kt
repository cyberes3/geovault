package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class ValidationRepository(private val context: Context) {
    suspend fun validateConnection(): String = withContext(Dispatchers.IO) {
        val serverUrl = GeovaultAuthManager.normalizeServerUrl(GeovaultAuthManager.getServerUrl(context))
        if (serverUrl.isBlank() || !GeovaultAuthManager.isLoggedIn(context)) {
            return@withContext "Please configure settings first"
        }
        val client = RetrofitClient.getAuthenticatedOkHttpClient(context).newBuilder().retryOnConnectionFailure(true).build()
        val request = Request.Builder().url("$serverUrl/api/user/status/").build()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    "✓ Connected to GeoVault.\n\nShare a file to upload it or choose one using the button below."
                } else if (response.code == 401) {
                    "✗ Unauthorized.\n\nReconnect in Settings."
                } else if (response.code == 404) {
                    "✗ Not found.\n\nCheck your server URL."
                } else {
                    "✗ Request failed (${response.code})"
                }
            }
        } catch (e: Exception) {
            "${e.message ?: "Unknown error"}\n\nCheck your server URL and network connection."
        }
    }
}

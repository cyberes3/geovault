package com.geovault.common.auth

import android.util.Log
import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class FetchUserStatusResult(
    val email: String?,
    val isUserStatusEndpointReachable: Boolean,
)

internal class GeoVaultUserStatusClient(
    private val store: GeoVaultAuthStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun fetch(): FetchUserStatusResult {
        val serverUrl = GeoVaultServerUrl.parse(store.getServerUrl())
            ?: return FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
        val client = GeoVaultHttp.authenticatedClient().newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(serverUrl.resolve("/api/user/status/")).build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body.string() }.getOrDefault("")
                val statusCode = response.code
                if (statusCode == 200 && body.isNotEmpty()) {
                    val statusPayload = runCatching {
                        json.decodeFromString(UserStatusPayload.serializer(), body)
                    }.getOrNull()
                    if (statusPayload != null) {
                        val email = statusPayload.email?.trim().orEmpty()
                        if (email.isNotEmpty()) {
                            store.setCachedUserEmail(email)
                            return FetchUserStatusResult(email = email, isUserStatusEndpointReachable = true)
                        }
                        return FetchUserStatusResult(email = null, isUserStatusEndpointReachable = true)
                    }
                    FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
                } else if (statusCode == 401) {
                    // Reachable but session rejected. Sign-out is explicit via handleAuthFailure.
                    FetchUserStatusResult(email = null, isUserStatusEndpointReachable = true)
                } else {
                    FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserStatus failed: ${e.message}")
            FetchUserStatusResult(email = null, isUserStatusEndpointReachable = false)
        }
    }

    @Serializable
    private data class UserStatusPayload(
        val email: String? = null,
    )

    companion object {
        private const val TAG = "GeoVaultUserStatus"
    }
}

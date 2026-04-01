package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.messages.GeoVaultUploadMessageFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

enum class ValidationOutcome {
    Loading,
    Success,
    Error,
    Info
}

data class ValidationResult(
    val outcome: ValidationOutcome,
    val title: String,
    val message: String
)

class ValidationRepository(private val context: Context) {
    suspend fun validateConnection(): ValidationResult = withContext(Dispatchers.IO) {
        val serverUrl = GeovaultAuthManager.normalizeServerUrl(GeovaultAuthManager.getServerUrl(context))
        if (serverUrl.isBlank() || !GeovaultAuthManager.isLoggedIn(context)) {
            return@withContext ValidationResult(
                outcome = ValidationOutcome.Info,
                title = "Configuration Required",
                message = "Please configure settings first"
            )
        }
        val client = RetrofitClient.getAuthenticatedOkHttpClient(context).newBuilder().retryOnConnectionFailure(true).build()
        val request = Request.Builder().url("$serverUrl/api/user/status/").build()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ValidationResult(
                        outcome = ValidationOutcome.Success,
                        title = "Connected",
                        message = GeoVaultUploadMessageFormatter.validationConnected()
                    )
                } else if (response.code == 401) {
                    GeovaultAuthManager.handleAuthFailure(context)
                    ValidationResult(
                        outcome = ValidationOutcome.Error,
                        title = "Validation Failed",
                        message = GeoVaultUploadMessageFormatter.validationUnauthorized()
                    )
                } else if (response.code == 404) {
                    ValidationResult(
                        outcome = ValidationOutcome.Error,
                        title = "Validation Failed",
                        message = GeoVaultUploadMessageFormatter.validationNotFound()
                    )
                } else {
                    ValidationResult(
                        outcome = ValidationOutcome.Error,
                        title = "Validation Failed",
                        message = GeoVaultUploadMessageFormatter.validationRequestFailed(response.code)
                    )
                }
            }
        } catch (e: Exception) {
            ValidationResult(
                outcome = ValidationOutcome.Error,
                title = "Validation Failed",
                message = GeoVaultUploadMessageFormatter.validationConnectionFailed(e.message ?: "Unknown error")
            )
        }
    }
}

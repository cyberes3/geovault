package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.RetrofitClient
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.common.auth.ServerConfigService
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

class ValidationRepository(
    context: Context,
    private val serverConfigService: ServerConfigService = GeovaultAuthServices(context),
    private val authSessionService: AuthSessionService = GeovaultAuthServices(context)
) {
    private val appContext = context.applicationContext

    suspend fun validateConnection(): ValidationResult = withContext(Dispatchers.IO) {
        val serverUrl = serverConfigService.getNormalizedServerUrl()
        if (serverUrl.isBlank() || !authSessionService.isLoggedIn()) {
            return@withContext ValidationResult(
                outcome = ValidationOutcome.Info,
                title = "Configuration Required",
                message = "Please configure settings first"
            )
        }
        val client = RetrofitClient.getAuthenticatedOkHttpClient(appContext).newBuilder().retryOnConnectionFailure(true).build()
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
                    authSessionService.handleAuthFailure()
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

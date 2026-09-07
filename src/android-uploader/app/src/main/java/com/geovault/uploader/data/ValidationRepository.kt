package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.auth.ServerConfigService
import com.geovault.common.messages.GeoVaultUploadMessageFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    authSession: GeoVaultAuthSession = GeoVaultAuthSession.get(),
    private val serverConfigService: ServerConfigService = authSession,
    private val authSessionService: AuthSessionService = authSession,
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
        val status = GeoVaultAuthSession.get().fetchUserStatusWithResult()
        return@withContext when {
            status.email != null -> ValidationResult(
                outcome = ValidationOutcome.Success,
                title = "Connected",
                message = GeoVaultUploadMessageFormatter.validationConnected(),
            )
            status.isUserStatusEndpointReachable -> ValidationResult(
                outcome = ValidationOutcome.Error,
                title = "Validation Failed",
                message = GeoVaultUploadMessageFormatter.validationUnauthorized(),
            )
            else -> ValidationResult(
                outcome = ValidationOutcome.Error,
                title = "Validation Failed",
                message = GeoVaultUploadMessageFormatter.validationConnectionFailed("Could not reach server"),
            )
        }
    }
}

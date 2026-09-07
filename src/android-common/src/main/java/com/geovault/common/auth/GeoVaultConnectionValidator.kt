package com.geovault.common.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface GeoVaultConnectionStatus {
    data object NotConfigured : GeoVaultConnectionStatus
    data class Connected(val email: String) : GeoVaultConnectionStatus
    data object Unauthorized : GeoVaultConnectionStatus
    data object Unreachable : GeoVaultConnectionStatus
}

class GeoVaultConnectionValidator(
    private val serverConfigService: ServerConfigService,
    private val authSessionService: AuthSessionService,
    private val fetchUserStatus: () -> FetchUserStatusResult,
) {
    constructor(session: GeoVaultAuthSession) : this(
        serverConfigService = session,
        authSessionService = session,
        fetchUserStatus = session::fetchUserStatusWithResult,
    )

    suspend fun validate(): GeoVaultConnectionStatus = withContext(Dispatchers.IO) {
        val serverUrl = serverConfigService.getNormalizedServerUrl()
        if (serverUrl.isBlank() || !authSessionService.isLoggedIn()) {
            return@withContext GeoVaultConnectionStatus.NotConfigured
        }
        val status = fetchUserStatus()
        val email = status.email?.trim().orEmpty()
        when {
            email.isNotEmpty() -> GeoVaultConnectionStatus.Connected(email)
            status.isUserStatusEndpointReachable -> GeoVaultConnectionStatus.Unauthorized
            else -> GeoVaultConnectionStatus.Unreachable
        }
    }
}

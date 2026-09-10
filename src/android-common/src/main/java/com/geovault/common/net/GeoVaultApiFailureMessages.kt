package com.geovault.common.net

object GeoVaultApiFailureMessages {
    fun format(failure: GeoVaultApiFailure): String {
        val missingServer = failure.httpCode == null &&
            failure.serverMessage?.contains("server url", ignoreCase = true) == true
        if (missingServer) {
            return "Server URL is not configured."
        }
        return when (GeoVaultHttpFailureClassifier.classify(failure)) {
            GeoVaultHttpFailureKind.Auth -> "Session expired. Sign in again."
            GeoVaultHttpFailureKind.NotFound -> "Not found."
            GeoVaultHttpFailureKind.RetryableNetwork -> "Network error. Try again."
            GeoVaultHttpFailureKind.PermanentClient ->
                failure.serverMessage?.takeIf { it.isNotBlank() }
                    ?: "Request could not be validated."
            GeoVaultHttpFailureKind.RetryableServer ->
                "Server error (${failure.httpCode ?: 0})."
            GeoVaultHttpFailureKind.Conflict,
            GeoVaultHttpFailureKind.Unknown -> "Something went wrong."
        }
    }
}

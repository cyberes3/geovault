package com.geovault.tracker.data

import android.content.Context
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.tracker.R

object TrackerApiFailureMessages {
    fun format(context: Context, failure: GeoVaultApiFailure): String {
        val missingServer = failure.httpCode == null &&
            failure.serverMessage?.contains("server url", ignoreCase = true) == true
        if (missingServer) {
            return context.getString(R.string.trackers_error_missing_server)
        }
        return when (GeoVaultHttpFailureClassifier.classify(failure)) {
            GeoVaultHttpFailureKind.Auth -> context.getString(R.string.trackers_error_unauthorized)
            GeoVaultHttpFailureKind.NotFound -> context.getString(R.string.trackers_error_not_found)
            GeoVaultHttpFailureKind.RetryableNetwork -> context.getString(R.string.trackers_error_network)
            GeoVaultHttpFailureKind.PermanentClient ->
                failure.serverMessage?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.trackers_error_validation)
            GeoVaultHttpFailureKind.RetryableServer ->
                context.getString(R.string.trackers_error_server, failure.httpCode ?: 0)
            GeoVaultHttpFailureKind.Conflict,
            GeoVaultHttpFailureKind.Unknown -> context.getString(R.string.trackers_error_unknown)
        }
    }
}

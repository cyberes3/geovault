package com.geovault.uploader.presentation

import com.geovault.common.messages.GeoVaultUploadMessageFormatter
import com.geovault.common.net.GeoVaultApiFailure

object UploaderFailureMessages {
    fun format(failure: GeoVaultApiFailure): String {
        val code = failure.httpCode
        if (code != null) {
            return GeoVaultUploadMessageFormatter.fromStatusCode(
                code,
                failure.serverMessage.orEmpty(),
            )
        }
        val detail = failure.serverMessage?.trim().orEmpty()
        return if (detail.isNotEmpty()) {
            GeoVaultUploadMessageFormatter.validationConnectionFailed(detail)
        } else {
            GeoVaultUploadMessageFormatter.validationConnectionFailed("Connection failed")
        }
    }
}

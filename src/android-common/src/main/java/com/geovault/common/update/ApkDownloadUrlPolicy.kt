package com.geovault.common.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ApkDownloadUrlPolicy {
    fun isHttps(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.isHttps
    }

    fun requireHttps(url: String): Result<HttpUrl> {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("invalid_download_url"))
        if (!parsed.isHttps) {
            return Result.failure(IllegalArgumentException("insecure_download_url"))
        }
        return Result.success(parsed)
    }
}

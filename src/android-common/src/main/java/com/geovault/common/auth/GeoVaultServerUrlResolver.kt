package com.geovault.common.auth

import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class GeoVaultServerUrlResolver {
    private val resolveClient = GeoVaultHttp.bootstrapClient().newBuilder()
        .connectTimeout(
            GeoVaultAuthConnectTimeouts.SERVER_URL_RESOLVE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        .readTimeout(
            GeoVaultAuthConnectTimeouts.SERVER_URL_RESOLVE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        .followRedirects(true)
        .build()

    fun resolveToCanonical(url: String): Result<String> {
        val parsed = GeoVaultServerUrl.parse(url)
            ?: return Result.failure(IllegalArgumentException("Server URL must be http/https"))
        if (parsed.value.startsWith("https://")) {
            return Result.success(parsed.value)
        }
        if (!parsed.value.startsWith("http://")) {
            return Result.failure(IllegalArgumentException("Server URL must be http/https"))
        }
        val request = Request.Builder().url(parsed.resolve("/")).head().build()
        return try {
            resolveClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url
                val defaultPort = if (finalUrl.scheme == "https") 443 else 80
                val resolved = "${finalUrl.scheme}://${finalUrl.host}" +
                    if (finalUrl.port != defaultPort) ":${finalUrl.port}" else ""
                Result.success(resolved)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

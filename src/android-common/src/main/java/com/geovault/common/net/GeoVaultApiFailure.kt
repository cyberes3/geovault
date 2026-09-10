package com.geovault.common.net

import okhttp3.Response as OkHttpResponse
import retrofit2.Response as RetrofitResponse

/**
 * Structured API / transport failure. Repositories throw this instead of embedding HTTP
 * codes in free-text messages.
 */
class GeoVaultApiFailure(
    val httpCode: Int?,
    val serverMessage: String?,
    val operation: String? = null,
    override val cause: Throwable? = null,
) : Exception(buildMessage(httpCode, serverMessage, operation), cause) {

    fun userMessage(): String {
        val detail = serverMessage?.takeIf { it.isNotBlank() }
            ?: httpCode?.let { "HTTP $it" }
            ?: "Unknown error"
        return if (httpCode != null) "Server Error: $detail" else "Network failed: $detail"
    }

    companion object {
        fun fromOkHttp(response: OkHttpResponse, operation: String? = null, body: String? = null): GeoVaultApiFailure {
            val raw = body ?: runCatching { response.body.string() }.getOrNull()
            return GeoVaultApiFailure(
                httpCode = response.code,
                serverMessage = parseServerMessage(raw),
                operation = operation,
            )
        }

        fun fromRetrofit(response: RetrofitResponse<*>, operation: String? = null): GeoVaultApiFailure {
            val body = runCatching { response.errorBody()?.string() }.getOrNull()
            return GeoVaultApiFailure(
                httpCode = response.code(),
                serverMessage = parseServerMessage(body),
                operation = operation,
            )
        }

        fun fromThrowable(throwable: Throwable, operation: String? = null): GeoVaultApiFailure {
            if (throwable is GeoVaultApiFailure) return throwable
            return GeoVaultApiFailure(
                httpCode = null,
                serverMessage = throwable.message,
                operation = operation,
                cause = throwable,
            )
        }

        private fun parseServerMessage(body: String?): String? {
            return ErrorEnvelope.parse(body)?.error
                ?: body?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun buildMessage(httpCode: Int?, serverMessage: String?, operation: String?): String {
            val parts = buildList {
                if (!operation.isNullOrBlank()) add(operation)
                if (httpCode != null) add("HTTP $httpCode")
                if (!serverMessage.isNullOrBlank()) add(serverMessage)
            }
            return parts.joinToString(": ").ifBlank { "Request failed" }
        }
    }
}

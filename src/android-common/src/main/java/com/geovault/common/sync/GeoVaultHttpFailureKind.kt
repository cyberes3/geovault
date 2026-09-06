package com.geovault.common.sync

import com.geovault.common.net.GeoVaultApiFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Coarse classification of API/network failures for offline queue and retry policy.
 */
enum class GeoVaultHttpFailureKind {
    /** Transient network/transport errors — keep queued and retry. */
    RetryableNetwork,

    /** Auth is broken — surface re-login; do not pretend the user chose offline save. */
    Auth,

    /** Client/validation errors that will not succeed on retry without editing. */
    PermanentClient,

    /** Resource missing (e.g. deleted on server). */
    NotFound,

    /** Duplicate / conflict (e.g. HTTP 409). */
    Conflict,

    /** Other server errors — keep queued and retry. */
    RetryableServer,

    /** Unclassified. */
    Unknown,
}

object GeoVaultHttpFailureClassifier {
    fun classify(failure: GeoVaultApiFailure): GeoVaultHttpFailureKind {
        return classify(failure.httpCode, failure.serverMessage, failure.cause)
    }

    fun classify(httpCode: Int?, errorMessage: String?, cause: Throwable?): GeoVaultHttpFailureKind {
        if (cause != null && isRetryableTransport(cause)) {
            return GeoVaultHttpFailureKind.RetryableNetwork
        }
        val code = httpCode
        if (code != null) {
            return when (code) {
                401, 403 -> GeoVaultHttpFailureKind.Auth
                404 -> GeoVaultHttpFailureKind.NotFound
                409 -> GeoVaultHttpFailureKind.Conflict
                in 400..499 -> GeoVaultHttpFailureKind.PermanentClient
                in 500..599 -> GeoVaultHttpFailureKind.RetryableServer
                else -> GeoVaultHttpFailureKind.Unknown
            }
        }
        val message = errorMessage.orEmpty()
        return when {
            message.contains("already exists", ignoreCase = true) -> GeoVaultHttpFailureKind.Conflict
            message.contains("Resource not found", ignoreCase = true) -> GeoVaultHttpFailureKind.NotFound
            message.contains("Unauthorized", ignoreCase = true) ||
                message.contains("Authentication", ignoreCase = true) -> GeoVaultHttpFailureKind.Auth
            else -> GeoVaultHttpFailureKind.Unknown
        }
    }

    fun classifyThrowable(error: Throwable): GeoVaultHttpFailureKind {
        if (error is GeoVaultApiFailure) return classify(error)
        if (isRetryableTransport(error)) return GeoVaultHttpFailureKind.RetryableNetwork
        return classify(httpCode = null, errorMessage = error.message, cause = error)
    }

    fun isTransientTransport(error: Throwable): Boolean {
        return classifyThrowable(error) == GeoVaultHttpFailureKind.RetryableNetwork
    }

    private fun isRetryableTransport(t: Throwable): Boolean {
        var cur: Throwable? = t
        val seen = mutableSetOf<Throwable>()
        while (cur != null && cur !in seen) {
            seen.add(cur)
            when (cur) {
                is SSLException -> return false
                is UnknownHostException, is SocketTimeoutException, is ConnectException -> return true
                else -> {
                    if (cur.javaClass.name == "android.system.GaiException") return true
                }
            }
            cur = cur.cause
        }
        return t is IOException && t !is SSLException
    }
}

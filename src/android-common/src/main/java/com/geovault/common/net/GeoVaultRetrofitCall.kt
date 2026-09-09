package com.geovault.common.net

import com.geovault.common.auth.GeoVaultAuthSession
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

suspend fun <T> Call<T>.await(operation: String? = null): T {
    return awaitResponse(operation).bodyOrThrow(operation)
}

suspend fun <T> Call<T>.awaitResponse(operation: String? = null): Response<T> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                if (continuation.isCancelled) return
                continuation.resume(response)
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(GeoVaultApiFailure.fromThrowable(t, operation))
            }
        })
    }

fun <T> Response<T>.bodyOrThrow(operation: String? = null): T {
    if (!isSuccessful) {
        resetSessionIfForbidden(code())
        throw GeoVaultApiFailure.fromRetrofit(this, operation)
    }
    return body() ?: throw GeoVaultApiFailure(
        httpCode = code(),
        serverMessage = "Empty response",
        operation = operation,
    )
}

fun Response<*>.successOrThrow(operation: String? = null) {
    if (!isSuccessful) {
        resetSessionIfForbidden(code())
        throw GeoVaultApiFailure.fromRetrofit(this, operation)
    }
}

private fun resetSessionIfForbidden(httpCode: Int) {
    if (httpCode == 403) {
        runCatching { GeoVaultAuthSession.get().handleAuthFailure() }
    }
}

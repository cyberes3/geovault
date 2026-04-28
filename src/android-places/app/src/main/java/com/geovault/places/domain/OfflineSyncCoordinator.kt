package com.geovault.places.domain

import com.geovault.places.model.FeatureCollection
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay

class OfflineSyncCoordinator(
    private val repository: PlacesRemoteDataSource,
    private val cacheStore: PlacesOfflineStore,
    private val syncExecutor: OfflineSyncExecutor,
    private val navigationRetryFlusher: NavigationRetryFlusher,
    private val serverUrlProvider: () -> String,
) {
    suspend fun fetchAndCacheServerSnapshot(): SnapshotFetchResult {
        val firstFetch = fetchPlacesResilient()
        val firstCollection = firstFetch.getOrElse { error ->
            return SnapshotFetchResult.Failed(snapshotFailureMessage(error))
        }
        cacheStore.setCached(firstCollection, System.currentTimeMillis())
        return SnapshotFetchResult.Success
    }

    suspend fun runPendingReplayAndCanonicalRefresh(): ReplayExecutionResult {
        flushPendingNavigations()
        val syncResult = syncExecutor.runSync()

        var warning: String? = null
        if (syncResult.hadQueuedItems && (syncResult.successCount > 0 || syncResult.queueBecameEmpty)) {
            val secondFetch = fetchPlacesResilient()
            secondFetch.onSuccess { canonical ->
                cacheStore.setCached(canonical, System.currentTimeMillis())
            }.onFailure { error ->
                warning = "Synced changes, but failed to refresh latest server data: ${error.message ?: "Unknown error"}"
            }
        }
        flushPendingNavigations()

        return ReplayExecutionResult(
            syncResult = syncResult,
            warningMessage = warning,
        )
    }

    private fun flushPendingNavigations() {
        val serverUrl = serverUrlProvider()
        if (serverUrl.isBlank()) return
        navigationRetryFlusher.flushPending(serverUrl)
    }

    /**
     * Re-tries snapshot fetch when the OS/network stack returns transient errors (e.g. cold-start
     * DNS racing default-network validation). Does not retry HTTP/server errors from the API.
     */
    private suspend fun fetchPlacesResilient(): Result<FeatureCollection> {
        var last: Result<FeatureCollection>? = null
        repeat(SNAPSHOT_FETCH_MAX_ATTEMPTS) { attempt ->
            val result = repository.fetchPlacesCancellable()
            last = result
            if (result.isSuccess) return result
            val err = result.exceptionOrNull()!!
            if (attempt < SNAPSHOT_FETCH_MAX_ATTEMPTS - 1 && isTransientNetworkFailure(err)) {
                delay(SNAPSHOT_FETCH_RETRY_DELAYS_MS[attempt])
            } else {
                return result
            }
        }
        return last!!
    }

    private fun snapshotFailureMessage(error: Throwable): String {
        val details = error.message?.trim().orEmpty()
        return if (details.startsWith("Server error:", ignoreCase = true)) {
            "Server Error: ${details.removePrefix("Server error:").trim()}"
        } else {
            "Network failed: ${if (details.isNotEmpty()) details else "Unknown error"}"
        }
    }

    private fun isTransientNetworkFailure(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        if (msg.startsWith("Server error:", ignoreCase = true)) return false
        if (msg == "Server returned no data") return false

        var cur: Throwable? = t
        val seen = mutableSetOf<Throwable>()
        while (cur != null && cur !in seen) {
            seen.add(cur)
            when (cur) {
                is SSLException -> return false
                is UnknownHostException -> return true
                is SocketTimeoutException -> return true
                is ConnectException -> return true
                else -> {
                    if (cur.javaClass.name == GAI_EXCEPTION_CLASS_NAME) return true
                }
            }
            cur = cur.cause
        }
        return t is IOException && t !is SSLException
    }

    companion object {
        private const val GAI_EXCEPTION_CLASS_NAME = "android.system.GaiException"
        private const val SNAPSHOT_FETCH_MAX_ATTEMPTS = 3
        private val SNAPSHOT_FETCH_RETRY_DELAYS_MS = longArrayOf(200L, 450L)
    }
}

sealed class SnapshotFetchResult {
    data object Success : SnapshotFetchResult()
    data class Failed(
        val message: String,
    ) : SnapshotFetchResult()
}

data class ReplayExecutionResult(
    val syncResult: SyncResult,
    val warningMessage: String? = null,
)

package com.geovault.places.domain

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.places.model.FeatureCollection
import kotlinx.coroutines.delay

class OfflineSyncCoordinator(
    private val repository: PlacesRemoteDataSource,
    private val cacheStore: PlacesOfflineStore,
    private val syncExecutor: OfflineSyncExecutor,
    private val navigationRetryFlusher: NavigationRetryFlusher,
    private val serverUrlProvider: () -> String,
) {
    suspend fun fetchAndCacheServerSnapshot(): SnapshotFetchResult {
        GeoVaultCaptureLog.i(TAG, "fetchAndCacheServerSnapshot start")
        val firstFetch = fetchPlacesResilient()
        val firstCollection = firstFetch.getOrElse { error ->
            val message = snapshotFailureMessage(error)
            GeoVaultCaptureLog.e(TAG, "fetchAndCacheServerSnapshot failed: $message", error)
            return SnapshotFetchResult.Failed(message)
        }
        cacheStore.setCached(firstCollection, System.currentTimeMillis())
        GeoVaultCaptureLog.i(
            TAG,
            "fetchAndCacheServerSnapshot ok count=${firstCollection.features.size}",
        )
        return SnapshotFetchResult.Success
    }

    suspend fun runPendingReplayAndCanonicalRefresh(): ReplayExecutionResult {
        GeoVaultCaptureLog.i(TAG, "runPendingReplayAndCanonicalRefresh start")
        flushPendingNavigations()
        val syncResult = syncExecutor.runSync()

        var warning: String? = null
        if (syncResult.hadQueuedItems && (syncResult.successCount > 0 || syncResult.queueBecameEmpty)) {
            val secondFetch = fetchPlacesResilient()
            secondFetch.onSuccess { canonical ->
                cacheStore.setCached(canonical, System.currentTimeMillis())
                GeoVaultCaptureLog.i(
                    TAG,
                    "canonical refresh ok count=${canonical.features.size}",
                )
            }.onFailure { error ->
                val warningMessage =
                    "Synced changes, but failed to refresh latest server data: ${error.message ?: "Unknown error"}"
                warning = warningMessage
                GeoVaultCaptureLog.w(TAG, warningMessage, error)
            }
        }
        flushPendingNavigations()

        GeoVaultCaptureLog.i(
            TAG,
            "runPendingReplayAndCanonicalRefresh done hadQueued=${syncResult.hadQueuedItems} " +
                "success=${syncResult.successCount} failed=${syncResult.failedCount} " +
                "warning=${warning != null}",
        )
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
            if (attempt < SNAPSHOT_FETCH_MAX_ATTEMPTS - 1 && GeoVaultHttpFailureClassifier.isTransientTransport(err)) {
                delay(SNAPSHOT_FETCH_RETRY_DELAYS_MS[attempt])
            } else {
                return result
            }
        }
        return last!!
    }

    private fun snapshotFailureMessage(error: Throwable): String {
        if (error is GeoVaultApiFailure) {
            val detail = error.serverMessage?.takeIf { it.isNotBlank() }
                ?: error.httpCode?.let { "HTTP $it" }
                ?: "Unknown error"
            return if (error.httpCode != null) "Server Error: $detail" else "Network failed: $detail"
        }
        val details = error.message?.trim().orEmpty()
        return "Network failed: ${if (details.isNotEmpty()) details else "Unknown error"}"
    }

    companion object {
        private const val TAG = "PlacesSyncCoordinator"
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

package com.geovault.places.domain

class OfflineSyncCoordinator(
    private val repository: PlacesRemoteDataSource,
    private val cacheStore: PlacesOfflineStore,
    private val syncExecutor: OfflineSyncExecutor,
    private val navigationRetryFlusher: NavigationRetryFlusher,
    private val serverUrlProvider: () -> String,
) {
    suspend fun fetchAndCacheServerSnapshot(): SnapshotFetchResult {
        val firstFetch = repository.fetchPlacesCancellable()
        val firstCollection = firstFetch.getOrElse { error ->
            val details = error.message?.trim().orEmpty()
            val message = if (details.startsWith("Server error:", ignoreCase = true)) {
                "Server Error: ${details.removePrefix("Server error:").trim()}"
            } else {
                "Network failed: ${if (details.isNotEmpty()) details else "Unknown error"}"
            }
            return SnapshotFetchResult.Failed(message)
        }
        cacheStore.setCached(firstCollection, System.currentTimeMillis())
        return SnapshotFetchResult.Success
    }

    suspend fun runPendingReplayAndCanonicalRefresh(): ReplayExecutionResult {
        flushPendingNavigations()
        val syncResult = syncExecutor.runSync()

        var warning: String? = null
        if (syncResult.hadQueuedItems && (syncResult.successCount > 0 || syncResult.queueBecameEmpty)) {
            val secondFetch = repository.fetchPlacesCancellable()
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

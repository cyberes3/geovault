package com.geovault.places.domain

import com.geovault.places.model.OfflineFeature
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SyncOfflinePlacesUseCase(
    private val repository: PlacesRemoteDataSource,
    private val cacheStore: PlacesOfflineStore,
    private val conflictResolutionPolicy: ConflictResolutionPolicy,
) : OfflineSyncExecutor {
    override suspend fun runSync(): SyncResult {
        val offline = cacheStore.getOfflineFeatures()
        if (offline.isEmpty()) {
            return SyncResult(
                hadQueuedItems = false,
                successCount = 0,
                failedCount = 0,
                queueBecameEmpty = true,
                conflictCount = 0,
                failures = emptyList(),
                events = emptyList(),
            )
        }

        var success = 0
        var failed = 0
        var conflictCount = 0
        val failures = mutableListOf<SyncFailure>()
        val events = mutableListOf<SyncEvent>()
        offline.forEach { item ->
            coroutineContext.ensureActive()
            when (val outcome = syncOne(item)) {
                is SyncItemOutcome.Success -> {
                    if (outcome.wasConflictCopy) conflictCount += 1
                    success += 1
                    cacheStore.removeOffline(item)
                    if (outcome.wasConflictCopy) {
                        events.add(
                            SyncEvent.ConflictSavedAsNew(
                                placeName = item.feature.properties.name ?: "Place"
                            )
                        )
                    }
                }
                is SyncItemOutcome.Failure -> {
                    failed += 1
                    failures.add(SyncFailure(item = item, reason = outcome.reason, message = outcome.message))
                    events.add(
                        SyncEvent.ItemFailed(
                            placeName = item.feature.properties.name ?: "Place",
                            reason = outcome.reason,
                            message = outcome.message,
                        )
                    )
                }
            }
        }
        return SyncResult(
            hadQueuedItems = true,
            successCount = success,
            failedCount = failed,
            queueBecameEmpty = cacheStore.getOfflineFeatures().isEmpty(),
            conflictCount = conflictCount,
            failures = failures,
            events = events,
        )
    }

    private fun syncOne(item: OfflineFeature): SyncItemOutcome {
        val feature = item.feature
        val dbId = feature.properties.database_id
        if (dbId == null) {
            val created = repository.createPlace(feature)
            return if (created.isSuccess) {
                SyncItemOutcome.Success(wasConflictCopy = false)
            } else {
                SyncItemOutcome.Failure(
                    reason = SyncFailureReason.CreateFailed,
                    message = created.exceptionOrNull()?.message
                )
            }
        }
        val original = item.original
        if (original == null) {
            val updated = repository.updatePlace(dbId, feature)
            return if (updated.isSuccess) {
                SyncItemOutcome.Success(wasConflictCopy = false)
            } else {
                SyncItemOutcome.Failure(
                    reason = SyncFailureReason.UpdateFailed,
                    message = updated.exceptionOrNull()?.message
                )
            }
        }
        val serverResult = repository.fetchPlace(dbId)
        val server = serverResult.getOrNull()
        if (server == null) {
            return SyncItemOutcome.Failure(
                reason = SyncFailureReason.FetchFailed,
                message = serverResult.exceptionOrNull()?.message
            )
        }
        if (conflictResolutionPolicy.hasServerChanged(original, server)) {
            val conflicted = conflictResolutionPolicy.buildConflictedCopy(feature)
            val created = repository.createPlace(conflicted)
            return if (created.isSuccess) {
                SyncItemOutcome.Success(wasConflictCopy = true)
            } else {
                SyncItemOutcome.Failure(
                    reason = SyncFailureReason.ConflictCreateFailed,
                    message = created.exceptionOrNull()?.message
                )
            }
        }
        val updated = repository.updatePlace(dbId, feature)
        return if (updated.isSuccess) {
            SyncItemOutcome.Success(wasConflictCopy = false)
        } else {
            SyncItemOutcome.Failure(
                reason = SyncFailureReason.UpdateFailed,
                message = updated.exceptionOrNull()?.message
            )
        }
    }

}

interface OfflineSyncExecutor {
    suspend fun runSync(): SyncResult
}

data class SyncResult(
    val hadQueuedItems: Boolean,
    val successCount: Int,
    val failedCount: Int,
    val queueBecameEmpty: Boolean,
    val conflictCount: Int,
    val failures: List<SyncFailure>,
    val events: List<SyncEvent>,
)

data class SyncFailure(
    val item: OfflineFeature,
    val reason: SyncFailureReason,
    val message: String?,
)

enum class SyncFailureReason {
    FetchFailed,
    ConflictCreateFailed,
    UpdateFailed,
    CreateFailed,
}

sealed class SyncEvent {
    data class ConflictSavedAsNew(
        val placeName: String,
    ) : SyncEvent()

    data class ItemFailed(
        val placeName: String,
        val reason: SyncFailureReason,
        val message: String?,
    ) : SyncEvent()
}

private sealed class SyncItemOutcome {
    data class Success(val wasConflictCopy: Boolean) : SyncItemOutcome()
    data class Failure(val reason: SyncFailureReason, val message: String?) : SyncItemOutcome()
}

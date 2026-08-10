package com.geovault.places.domain

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.common.sync.GeoVaultQueuedSyncFailurePolicy
import com.geovault.common.sync.GeoVaultQueuedSyncItemDisposition
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class SyncOfflinePlacesUseCase(
    private val repository: PlacesRemoteDataSource,
    private val cacheStore: PlacesOfflineStore,
    private val conflictResolutionPolicy: ConflictResolutionPolicy,
) : OfflineSyncExecutor {
    override suspend fun runSync(): SyncResult {
        val offline = cacheStore.getOfflineFeatures()
        if (offline.isEmpty()) {
            GeoVaultCaptureLog.i(TAG, "runSync skip: offline queue empty")
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

        GeoVaultCaptureLog.i(TAG, "runSync start queued=${offline.size}")
        var success = 0
        var failed = 0
        var conflictCount = 0
        val failures = mutableListOf<SyncFailure>()
        val events = mutableListOf<SyncEvent>()
        offline.forEach { item ->
            coroutineContext.ensureActive()
            val placeName = item.feature.properties.name ?: "Place"
            val dbId = item.feature.properties.database_id
            GeoVaultCaptureLog.i(
                TAG,
                "syncOne start clientLocalId=${item.clientLocalId} name=$placeName " +
                    "databaseId=$dbId hasOriginal=${item.original != null}",
            )
            when (val outcome = syncOne(item)) {
                is SyncItemOutcome.Success -> {
                    if (outcome.wasConflictCopy) conflictCount += 1
                    success += 1
                    GeoVaultCaptureLog.i(
                        TAG,
                        "syncOne ok name=$placeName conflictCopy=${outcome.wasConflictCopy}",
                    )
                    if (outcome.wasConflictCopy) {
                        events.add(SyncEvent.ConflictSavedAsNew(placeName = placeName))
                    }
                }
                is SyncItemOutcome.Failure -> {
                    failed += 1
                    failures.add(
                        SyncFailure(
                            item = item,
                            reason = outcome.reason,
                            message = outcome.message,
                        ),
                    )
                    events.add(
                        SyncEvent.ItemFailed(
                            placeName = placeName,
                            reason = outcome.reason,
                            message = outcome.message,
                        ),
                    )
                    GeoVaultCaptureLog.e(
                        TAG,
                        "syncOne failed name=$placeName reason=${outcome.reason} " +
                            "message=${outcome.message} dropped=${outcome.droppedFromQueue}",
                    )
                }
            }
        }
        val result = SyncResult(
            hadQueuedItems = true,
            successCount = success,
            failedCount = failed,
            queueBecameEmpty = cacheStore.getOfflineFeatures().isEmpty(),
            conflictCount = conflictCount,
            failures = failures,
            events = events,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "runSync done success=$success failed=$failed conflicts=$conflictCount " +
                "queueEmpty=${result.queueBecameEmpty}",
        )
        return result
    }

    private suspend fun syncOne(item: OfflineFeature): SyncItemOutcome {
        val feature = item.feature
        val dbId = feature.properties.database_id
        if (dbId == null) {
            return handleWriteResult(
                item = item,
                result = repository.createPlace(feature),
                failureReason = SyncFailureReason.CreateFailed,
                wasConflictCopy = false,
            )
        }
        val original = item.original
        if (original == null) {
            return handleUpdate(item, dbId, feature)
        }
        val serverResult = repository.fetchPlace(dbId)
        val server = serverResult.getOrNull()
        if (server == null) {
            return handleFetchFailure(item, feature, serverResult.exceptionOrNull())
        }
        if (conflictResolutionPolicy.hasServerChanged(original, server)) {
            GeoVaultCaptureLog.w(
                TAG,
                "syncOne conflict detected id=$dbId name=${feature.properties.name}; saving as new copy",
            )
            val conflicted = conflictResolutionPolicy.buildConflictedCopy(feature)
            return handleWriteResult(
                item = item,
                result = repository.createPlace(conflicted),
                failureReason = SyncFailureReason.ConflictCreateFailed,
                wasConflictCopy = true,
            )
        }
        return handleUpdate(item, dbId, feature)
    }

    private suspend fun handleUpdate(
        item: OfflineFeature,
        dbId: Int,
        feature: Feature,
    ): SyncItemOutcome {
        val updated = repository.updatePlace(dbId, feature)
        if (updated.isSuccess) {
            return commitSuccess(item, updated.getOrNull()!!, wasConflictCopy = false)
        }
        val error = updated.exceptionOrNull()!!
        val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
        val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
        if (disposition == GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard) {
            GeoVaultCaptureLog.w(
                TAG,
                "syncOne update target missing; recreating as create " +
                    "clientLocalId=${item.clientLocalId}",
            )
            val asCreate = feature.copy(
                properties = feature.properties.copy(database_id = null),
            )
            return handleWriteResult(
                item = item,
                result = repository.createPlace(asCreate),
                failureReason = SyncFailureReason.CreateFailed,
                wasConflictCopy = false,
            )
        }
        return applyFailureDisposition(
            item = item,
            reason = SyncFailureReason.UpdateFailed,
            message = error.message,
            kind = kind,
            disposition = disposition,
        )
    }

    private suspend fun handleFetchFailure(
        item: OfflineFeature,
        feature: Feature,
        error: Throwable?,
    ): SyncItemOutcome {
        val err = error ?: Exception("Fetch failed")
        val kind = GeoVaultHttpFailureClassifier.classifyThrowable(err)
        val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
        if (disposition == GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard) {
            val asCreate = feature.copy(
                properties = feature.properties.copy(database_id = null),
            )
            return handleWriteResult(
                item = item,
                result = repository.createPlace(asCreate),
                failureReason = SyncFailureReason.CreateFailed,
                wasConflictCopy = false,
            )
        }
        return applyFailureDisposition(
            item = item,
            reason = SyncFailureReason.FetchFailed,
            message = err.message,
            kind = kind,
            disposition = disposition,
        )
    }

    private suspend fun handleWriteResult(
        item: OfflineFeature,
        result: Result<Feature>,
        failureReason: SyncFailureReason,
        wasConflictCopy: Boolean,
    ): SyncItemOutcome {
        if (result.isSuccess) {
            return commitSuccess(item, result.getOrNull()!!, wasConflictCopy)
        }
        val error = result.exceptionOrNull()!!
        val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
        val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
        if (disposition == GeoVaultQueuedSyncItemDisposition.ResolveConflict &&
            failureReason == SyncFailureReason.CreateFailed
        ) {
            // Duplicate create (409): keep as conflict copy attempt via policy name.
            GeoVaultCaptureLog.w(
                TAG,
                "syncOne create conflict clientLocalId=${item.clientLocalId}; " +
                    "saving conflicted copy",
            )
            val conflicted = conflictResolutionPolicy.buildConflictedCopy(item.feature)
            val retry = repository.createPlace(conflicted)
            if (retry.isSuccess) {
                return commitSuccess(item, retry.getOrNull()!!, wasConflictCopy = true)
            }
            val retryError = retry.exceptionOrNull()!!
            return applyFailureDisposition(
                item = item,
                reason = SyncFailureReason.ConflictCreateFailed,
                message = retryError.message,
                kind = GeoVaultHttpFailureClassifier.classifyThrowable(retryError),
                disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(
                    GeoVaultHttpFailureClassifier.classifyThrowable(retryError),
                ),
            )
        }
        return applyFailureDisposition(
            item = item,
            reason = failureReason,
            message = error.message,
            kind = kind,
            disposition = disposition,
        )
    }

    private fun commitSuccess(
        item: OfflineFeature,
        serverFeature: Feature,
        wasConflictCopy: Boolean,
    ): SyncItemOutcome {
        cacheStore.applyServerFeature(serverFeature)
        cacheStore.removeOffline(item.clientLocalId)
        return SyncItemOutcome.Success(wasConflictCopy = wasConflictCopy)
    }

    private fun applyFailureDisposition(
        item: OfflineFeature,
        reason: SyncFailureReason,
        message: String?,
        kind: GeoVaultHttpFailureKind,
        disposition: GeoVaultQueuedSyncItemDisposition,
    ): SyncItemOutcome {
        val dropped = when (disposition) {
            GeoVaultQueuedSyncItemDisposition.DropAndSurface -> {
                cacheStore.removeOffline(item.clientLocalId)
                true
            }
            GeoVaultQueuedSyncItemDisposition.RequireAuth,
            GeoVaultQueuedSyncItemDisposition.KeepRetrying,
            GeoVaultQueuedSyncItemDisposition.ResolveConflict,
            GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard -> false
        }
        val surfacedMessage = when (disposition) {
            GeoVaultQueuedSyncItemDisposition.RequireAuth ->
                message?.takeIf { it.isNotBlank() }
                    ?: "Sign in again to sync '${item.feature.properties.name ?: "place"}'"
            else -> message
        }
        GeoVaultCaptureLog.w(
            TAG,
            "syncOne disposition=$disposition kind=$kind dropped=$dropped " +
                "clientLocalId=${item.clientLocalId}",
        )
        return SyncItemOutcome.Failure(
            reason = reason,
            message = surfacedMessage,
            droppedFromQueue = dropped,
        )
    }

    companion object {
        private const val TAG = "PlacesOfflineSync"
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
    data class Failure(
        val reason: SyncFailureReason,
        val message: String?,
        val droppedFromQueue: Boolean = false,
    ) : SyncItemOutcome()
}

package com.geovault.places.domain

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.sync.GeoVaultHttpFailureClassifier
import com.geovault.common.sync.GeoVaultHttpFailureKind
import com.geovault.common.sync.GeoVaultQueuedSyncFailurePolicy
import com.geovault.common.sync.GeoVaultQueuedSyncItemDisposition
import com.geovault.places.data.PlacesMutableStore
import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy

sealed class DeletePlaceOutcome {
    data object DeletedOnline : DeletePlaceOutcome()
    data object DiscardedLocal : DeletePlaceOutcome()
    data class Reverted(val message: String) : DeletePlaceOutcome()
    data class QueuedOffline(val message: String) : DeletePlaceOutcome()
    data class AuthRequired(val message: String) : DeletePlaceOutcome()
    data class Failed(val message: String) : DeletePlaceOutcome()
}

class DeletePlaceUseCase(
    private val remote: PlacesRemoteDataSource,
    private val store: PlacesMutableStore,
) {
    suspend fun deleteOrRevert(place: Place): DeletePlaceOutcome {
        return when (val pending = place.pending) {
            PendingChange.Create -> {
                store.remove(place.key)
                DeletePlaceOutcome.DiscardedLocal
            }
            is PendingChange.Update -> {
                store.upsert(place.copy(content = pending.baseline, pending = null))
                DeletePlaceOutcome.Reverted(PlacesOfflineBehaviorPolicy.REVERTED_CHANGES_MESSAGE)
            }
            is PendingChange.Delete -> {
                store.remove(place.key)
                DeletePlaceOutcome.DiscardedLocal
            }
            null -> deleteSynced(place)
        }
    }

    private suspend fun deleteSynced(place: Place): DeletePlaceOutcome {
        val serverId = place.serverId
        if (serverId == null) {
            store.remove(place.key)
            return DeletePlaceOutcome.DiscardedLocal
        }
        return try {
            remote.deletePlace(serverId)
            store.remove(place.key)
            DeletePlaceOutcome.DeletedOnline
        } catch (error: Throwable) {
            val failure = GeoVaultApiFailure.fromThrowable(error)
            val kind = GeoVaultHttpFailureClassifier.classify(failure)
            GeoVaultCaptureLog.e(TAG, "delete failed id=$serverId kind=$kind", error)
            when (GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)) {
                GeoVaultQueuedSyncItemDisposition.RequireAuth ->
                    DeletePlaceOutcome.AuthRequired(PlacesOfflineBehaviorPolicy.AUTH_REQUIRED_MESSAGE)
                GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard -> {
                    store.remove(place.key)
                    DeletePlaceOutcome.DeletedOnline
                }
                GeoVaultQueuedSyncItemDisposition.KeepRetrying,
                GeoVaultQueuedSyncItemDisposition.ResolveConflict -> {
                    store.upsert(place.copy(pending = PendingChange.Delete(place.content)))
                    DeletePlaceOutcome.QueuedOffline(PlacesOfflineBehaviorPolicy.DELETE_QUEUED_OFFLINE_MESSAGE)
                }
                GeoVaultQueuedSyncItemDisposition.DropAndSurface ->
                    DeletePlaceOutcome.Failed(
                        failure.serverMessage?.takeIf { it.isNotBlank() }
                            ?: PlacesOfflineBehaviorPolicy.DELETE_SERVER_ERROR_MESSAGE,
                    )
            }
        }
    }

    companion object {
        private const val TAG = "PlacesDelete"
    }
}

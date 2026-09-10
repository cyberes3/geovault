package com.geovault.places.domain

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultHttpFailureClassifier
import com.geovault.common.net.GeoVaultHttpFailureKind
import com.geovault.common.sync.GeoVaultQueuedSyncFailurePolicy
import com.geovault.common.sync.GeoVaultQueuedSyncItemDisposition
import com.geovault.places.data.PlacesMutableStore
import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.presentation.PlacesOfflineBehaviorPolicy

sealed class SavePlaceOutcome {
    data class SavedOnline(val place: Place) : SavePlaceOutcome()
    data class QueuedOffline(val place: Place, val message: String) : SavePlaceOutcome()
    data class AuthRequired(val message: String) : SavePlaceOutcome()
    data class FailedValidation(val message: String) : SavePlaceOutcome()
}

class SavePlaceUseCase(
    private val remote: PlacesRemoteDataSource,
    private val store: PlacesMutableStore,
) {
    suspend fun save(place: Place, baseline: PlaceContent?): SavePlaceOutcome {
        return try {
            val saved = if (place.serverId != null) {
                remote.updatePlace(place.serverId, place)
            } else {
                remote.createPlace(place)
            }
            store.commitSynced(place.key, saved)
            SavePlaceOutcome.SavedOnline(saved)
        } catch (error: Throwable) {
            val failure = GeoVaultApiFailure.fromThrowable(error)
            val kind = GeoVaultHttpFailureClassifier.classify(failure)
            val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
            GeoVaultCaptureLog.e(
                TAG,
                "save failed name=${place.content.name} kind=$kind disposition=$disposition",
                error,
            )
            when (disposition) {
                GeoVaultQueuedSyncItemDisposition.RequireAuth ->
                    SavePlaceOutcome.AuthRequired(PlacesOfflineBehaviorPolicy.AUTH_REQUIRED_MESSAGE)
                GeoVaultQueuedSyncItemDisposition.DropAndSurface ->
                    SavePlaceOutcome.FailedValidation(
                        failure.serverMessage?.takeIf { it.isNotBlank() }
                            ?: PlacesOfflineBehaviorPolicy.VALIDATION_FAILED_MESSAGE,
                    )
                GeoVaultQueuedSyncItemDisposition.KeepRetrying,
                GeoVaultQueuedSyncItemDisposition.ResolveConflict,
                GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard -> {
                    val pending = pendingChangeFor(place, baseline)
                    val queued = place.copy(pending = pending)
                    store.upsert(queued)
                    val message = when (kind) {
                        GeoVaultHttpFailureKind.RetryableNetwork,
                        GeoVaultHttpFailureKind.RetryableServer,
                        GeoVaultHttpFailureKind.Unknown ->
                            PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_NETWORK_MESSAGE
                        else -> PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_MESSAGE
                    }
                    SavePlaceOutcome.QueuedOffline(queued, message)
                }
            }
        }
    }

    private fun pendingChangeFor(place: Place, baseline: PlaceContent?): PendingChange {
        val existing = store.find(place.key)?.pending
        if (existing is PendingChange.Update) return existing
        if (existing is PendingChange.Create) return PendingChange.Create
        return if (place.serverId != null) {
            PendingChange.Update(baseline ?: place.content)
        } else {
            PendingChange.Create
        }
    }

    companion object {
        private const val TAG = "PlacesSave"
    }
}

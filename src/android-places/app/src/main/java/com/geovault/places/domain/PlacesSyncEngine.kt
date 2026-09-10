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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class PlacesSyncItemReport(
    val placeName: String,
    val success: Boolean,
    val conflictCopy: Boolean = false,
    val message: String? = null,
)

data class PlacesSyncReport(
    val hadQueuedItems: Boolean,
    val successCount: Int,
    val failedCount: Int,
    val conflictCount: Int,
    val items: List<PlacesSyncItemReport>,
    val warningMessage: String? = null,
)

class PlacesSyncEngine(
    private val remote: PlacesRemoteDataSource,
    private val store: PlacesMutableStore,
    private val conflictResolutionPolicy: ConflictResolutionPolicy,
    private val navigationFlusher: NavigationRetryFlusher,
) {
    suspend fun sync(): PlacesSyncReport {
        GeoVaultCaptureLog.i(TAG, "sync start")
        val pending = store.places().filter { it.pending != null }
        val items = mutableListOf<PlacesSyncItemReport>()
        pending.forEach { place ->
            coroutineContext.ensureActive()
            items.add(syncOne(place))
        }
        var warning: String? = null
        try {
            val server = fetchPlacesResilient()
            store.applyServerSnapshot(server)
        } catch (error: Throwable) {
            warning = GeoVaultApiFailure.fromThrowable(error).userMessage()
            GeoVaultCaptureLog.e(TAG, "sync snapshot failed: $warning", error)
            if (pending.isEmpty()) {
                return PlacesSyncReport(
                    hadQueuedItems = false,
                    successCount = 0,
                    failedCount = 0,
                    conflictCount = 0,
                    items = emptyList(),
                    warningMessage = warning,
                )
            }
        }
        runCatching { navigationFlusher.flushPending() }
        val success = items.count { it.success }
        val failed = items.count { !it.success }
        val conflicts = items.count { it.conflictCopy }
        GeoVaultCaptureLog.i(
            TAG,
            "sync done queued=${pending.size} success=$success failed=$failed conflicts=$conflicts",
        )
        return PlacesSyncReport(
            hadQueuedItems = pending.isNotEmpty(),
            successCount = success,
            failedCount = failed,
            conflictCount = conflicts,
            items = items,
            warningMessage = warning,
        )
    }

    private suspend fun syncOne(place: Place): PlacesSyncItemReport {
        val name = place.content.name
        return try {
            when (val pending = place.pending) {
                null -> PlacesSyncItemReport(placeName = name, success = true)
                PendingChange.Create -> pushCreate(place)
                is PendingChange.Update -> pushUpdate(place, pending)
                is PendingChange.Delete -> pushDelete(place)
            }
        } catch (error: Throwable) {
            coroutineContext.ensureActive()
            handleFailure(place, error)
        }
    }

    private suspend fun pushCreate(place: Place): PlacesSyncItemReport {
        return try {
            val created = remote.createPlace(place)
            store.commitSynced(place.key, created)
            PlacesSyncItemReport(placeName = place.content.name, success = true)
        } catch (error: Throwable) {
            if (GeoVaultHttpFailureClassifier.classifyThrowable(error) == GeoVaultHttpFailureKind.Conflict) {
                return saveConflictCopy(place)
            }
            throw error
        }
    }

    private suspend fun pushUpdate(place: Place, pending: PendingChange.Update): PlacesSyncItemReport {
        val serverId = place.serverId ?: return pushCreate(place.copy(pending = PendingChange.Create))
        val server = try {
            remote.fetchPlace(serverId)
        } catch (error: Throwable) {
            val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
            if (GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind) ==
                GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard
            ) {
                return pushCreate(place.copy(serverId = null, pending = PendingChange.Create))
            }
            throw error
        }
        if (conflictResolutionPolicy.hasServerChanged(pending.baseline, server.content)) {
            return saveConflictCopy(place)
        }
        return try {
            val updated = remote.updatePlace(serverId, place)
            store.commitSynced(place.key, updated)
            PlacesSyncItemReport(placeName = place.content.name, success = true)
        } catch (error: Throwable) {
            val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
            when (GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)) {
                GeoVaultQueuedSyncItemDisposition.ResolveConflict -> saveConflictCopy(place)
                GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard ->
                    pushCreate(place.copy(serverId = null, pending = PendingChange.Create))
                else -> throw error
            }
        }
    }

    private suspend fun pushDelete(place: Place): PlacesSyncItemReport {
        val serverId = place.serverId
        if (serverId != null) {
            try {
                remote.deletePlace(serverId)
            } catch (error: Throwable) {
                val kind = GeoVaultHttpFailureClassifier.classifyThrowable(error)
                if (GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind) !=
                    GeoVaultQueuedSyncItemDisposition.RecreateOrDiscard
                ) {
                    throw error
                }
            }
        }
        store.remove(place.key)
        return PlacesSyncItemReport(placeName = place.content.name, success = true)
    }

    private suspend fun saveConflictCopy(place: Place): PlacesSyncItemReport {
        val copy = conflictResolutionPolicy.buildConflictedCopy(place)
        val created = remote.createPlace(copy)
        store.remove(place.key)
        store.commitSynced(copy.key, created)
        return PlacesSyncItemReport(
            placeName = place.content.name,
            success = true,
            conflictCopy = true,
        )
    }

    private suspend fun handleFailure(place: Place, error: Throwable): PlacesSyncItemReport {
        val failure = GeoVaultApiFailure.fromThrowable(error)
        val kind = GeoVaultHttpFailureClassifier.classify(failure)
        val disposition = GeoVaultQueuedSyncFailurePolicy.dispositionFor(kind)
        val dropUpdate = disposition == GeoVaultQueuedSyncItemDisposition.DropAndSurface &&
            place.pending is PendingChange.Update
        if (disposition == GeoVaultQueuedSyncItemDisposition.DropAndSurface &&
            place.pending !is PendingChange.Update &&
            place.serverId != null
        ) {
            store.remove(place.key)
        }
        val message = if (dropUpdate || disposition == GeoVaultQueuedSyncItemDisposition.DropAndSurface) {
            failure.serverMessage?.takeIf { it.isNotBlank() } ?: failure.userMessage()
        } else {
            failure.message
        }
        GeoVaultCaptureLog.e(
            TAG,
            "syncOne failed name=${place.content.name} kind=$kind disposition=$disposition",
            error,
        )
        return PlacesSyncItemReport(
            placeName = place.content.name,
            success = false,
            message = message,
        )
    }

    private suspend fun fetchPlacesResilient(): List<Place> {
        var lastError: Throwable? = null
        repeat(SNAPSHOT_FETCH_MAX_ATTEMPTS) { attempt ->
            try {
                return remote.fetchPlaces()
            } catch (error: Throwable) {
                lastError = error
                if (attempt < SNAPSHOT_FETCH_MAX_ATTEMPTS - 1 &&
                    GeoVaultHttpFailureClassifier.isTransientTransport(error)
                ) {
                    delay(SNAPSHOT_FETCH_RETRY_DELAYS_MS[attempt])
                } else {
                    throw error
                }
            }
        }
        throw lastError ?: GeoVaultApiFailure(httpCode = null, serverMessage = "Fetch failed")
    }

    companion object {
        private const val TAG = "PlacesSync"
        private const val SNAPSHOT_FETCH_MAX_ATTEMPTS = 3
        private val SNAPSHOT_FETCH_RETRY_DELAYS_MS = longArrayOf(200L, 450L)
    }
}

interface NavigationRetryFlusher {
    suspend fun flushPending()
}

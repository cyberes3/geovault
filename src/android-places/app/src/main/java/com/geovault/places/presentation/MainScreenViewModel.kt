package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.sync.GeoVaultQueuedSyncMessageFormatter
import com.geovault.common.sync.GeoVaultQueuedSyncOutcome
import com.geovault.common.sync.GeoVaultRefreshTimeoutPolicy
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.common.update.VersionCheckResult
import com.geovault.places.BuildConfig
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.domain.SnapshotFetchResult
import com.geovault.places.domain.SyncEvent
import com.geovault.places.domain.SyncFailureReason
import com.geovault.places.domain.SyncResult
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val saved: List<Feature> = emptyList(),
    val offlineItems: List<OfflineFeature> = emptyList(),
    val selectedPlaceId: Int? = null,
    val lastSyncMillis: Long = 0L,
    val lastSyncLabel: String = "Not synced",
    val showSyncOverlay: Boolean = false,
    val syncOverlayTitle: String = "Syncing...",
    val syncOverlaySubtext: String = "Tap to cancel",
    val snackbar: GeoVaultSnackbarModel? = null,
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
)

class MainScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val services = PlacesAppServices.from(application)
    private val placesStore = services.placesStore()
    private val offlineSyncCoordinator = services.offlineSyncCoordinator()
    private val updateCoordinator = GeoVaultAndroidReleaseIdentity.Places.updateCoordinator(
        application = application,
        localFullCommitSha = { BuildConfig.GIT_COMMIT_SHA },
    )
    private var refreshJob: Job? = null
    private var refreshCancelMessage: String? = null
    private var refreshPhase: RefreshPhase = RefreshPhase.IDLE
    private var initialRefreshTriggered: Boolean = false
    private var snapshotCollectStarted: Boolean = false

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            updateCoordinator.promptState.collect { prompt ->
                _state.update { it.copy(updateAvailable = prompt.updateOrNull()) }
            }
        }
    }

    fun initialize() {
        if (!snapshotCollectStarted) {
            snapshotCollectStarted = true
            viewModelScope.launch {
                placesStore.snapshot.collect { publishFromSnapshot(it.cached, it.offline, it.lastSyncMillis) }
            }
        }
        publishFromSnapshot(
            placesStore.getCachedFeatures(),
            placesStore.getOfflineFeatures(),
            placesStore.getLastSyncTime(),
        )
    }

    fun onHostResumed() {
        // Snapshot Flow already keeps UI current; no separate reload needed.
    }

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        refreshAuthAndCache(accountState)
    }

    fun onSearchChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        val snap = placesStore.snapshot.value
        publishFromSnapshot(snap.cached, snap.offline, snap.lastSyncMillis)
    }

    fun refreshNow(
        statusText: String = "Syncing...",
        tapHintText: String = "Tap to cancel",
    ) {
        if (refreshJob?.isActive == true || _state.value.isRefreshing) {
            GeoVaultCaptureLog.i(TAG, "refreshNow ignored: already refreshing")
            return
        }
        refreshCancelMessage = null
        GeoVaultCaptureLog.i(
            TAG,
            "refreshNow start offlineQueued=${placesStore.getOfflineFeatures().size} " +
                "cached=${placesStore.getCachedFeatures().size}",
        )
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isRefreshing = true,
                    showSyncOverlay = true,
                    syncOverlayTitle = statusText,
                    syncOverlaySubtext = tapHintText,
                )
            }
            try {
                refreshPhase = RefreshPhase.FETCHING
                val fetchResult = withContext(Dispatchers.IO) {
                    withTimeout(GeoVaultRefreshTimeoutPolicy.DEFAULT_TIMEOUT_MS) {
                        offlineSyncCoordinator.fetchAndCacheServerSnapshot()
                    }
                }
                when (fetchResult) {
                    is SnapshotFetchResult.Failed -> {
                        GeoVaultCaptureLog.e(TAG, "refreshNow snapshot failed: ${fetchResult.message}")
                        showSnackbar(fetchResult.message, "main_error")
                    }
                    SnapshotFetchResult.Success -> {
                        refreshPhase = RefreshPhase.SYNCING
                        val replayResult = withContext(Dispatchers.IO) {
                            offlineSyncCoordinator.runPendingReplayAndCanonicalRefresh()
                        }
                        publishSyncOutcome(replayResult.syncResult)
                        replayResult.warningMessage?.let { showSnackbar(it, "main_warning") }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                GeoVaultCaptureLog.e(TAG, "refreshNow timed out")
                showSnackbar("Refresh timed out (10s)", "refresh_timeout")
            } catch (_: CancellationException) {
                val message = refreshCancelMessage ?: "Syncing cancelled"
                GeoVaultCaptureLog.w(TAG, "refreshNow cancelled: $message")
                showSnackbar(message, "refresh_cancelled")
            } finally {
                refreshPhase = RefreshPhase.IDLE
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        showSyncOverlay = false,
                        syncOverlayTitle = statusText,
                        syncOverlaySubtext = tapHintText,
                    )
                }
                GeoVaultCaptureLog.i(
                    TAG,
                    "refreshNow finished offlineQueued=${placesStore.getOfflineFeatures().size} " +
                        "cached=${placesStore.getCachedFeatures().size}",
                )
            }
        }
    }

    fun cancelRefresh() {
        refreshCancelMessage = PlacesOfflineBehaviorPolicy.REFRESH_CANCELLED_USING_CACHE_MESSAGE
        when (refreshPhase) {
            RefreshPhase.FETCHING, RefreshPhase.SYNCING -> {
                refreshJob?.cancel(CancellationException(refreshCancelMessage ?: "Cancelled by user"))
            }
            RefreshPhase.IDLE -> Unit
        }
    }

    fun revertOfflineChanges(item: OfflineFeature) {
        placesStore.removeOffline(item.clientLocalId)
        _state.update {
            it.copy(
                snackbar = GeoVaultSnackbarModel(
                    id = "offline_revert_${System.currentTimeMillis()}",
                    message = PlacesOfflineBehaviorPolicy.offlineRemovalMessage(item),
                ),
            )
        }
    }

    fun saveOffline(
        feature: Feature,
        original: Feature? = null,
        clientLocalId: String,
        snackbarMessage: String = PlacesOfflineBehaviorPolicy.SAVED_OFFLINE_MESSAGE,
    ) {
        GeoVaultCaptureLog.w(
            TAG,
            "saveOffline clientLocalId=$clientLocalId name=${feature.properties.name} " +
                "databaseId=${feature.properties.database_id} " +
                "hasOriginal=${original != null} " +
                "hasCreatedAt=${!feature.properties.created_at.isNullOrBlank()}",
        )
        placesStore.upsertOffline(
            clientLocalId = clientLocalId,
            feature = feature,
            original = original,
        )
        _state.update {
            it.copy(
                snackbar = GeoVaultSnackbarModel(
                    id = "offline_saved_${System.currentTimeMillis()}",
                    message = snackbarMessage,
                ),
            )
        }
    }

    fun applyUpdatedFeature(updated: Feature) {
        placesStore.updateCachedFeature(updated)
        placesStore.removeOfflineByFeature(updated)
        _state.update { it.copy(searchQuery = "") }
    }

    fun applyDeletedFeature(deleted: Feature) {
        placesStore.removeCachedFeature(deleted)
        placesStore.removeOfflineByFeature(deleted)
        _state.update { it.copy(searchQuery = "") }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    fun clearUpdateAvailable() {
        updateCoordinator.dismissPrompt()
    }

    fun showExternalError(message: String) {
        showSnackbar(message, "external_error")
    }

    fun setSelectedPlaceId(id: Int?) {
        _state.update { it.copy(selectedPlaceId = id) }
    }

    private fun refreshAuthAndCache(accountState: GeoVaultAccountUiState) {
        val wasAuthenticated = _state.value.isAuthenticated
        val loggedIn = accountState.isLoggedIn
        _state.update {
            it.copy(
                serverUrl = accountState.serverUrl,
                isAuthenticated = loggedIn,
                isConnecting = accountState.isConnecting,
                oauthUrl = null,
                lastSyncMillis = placesStore.getLastSyncTime(),
                lastSyncLabel = formatLastSyncLabel(placesStore.getLastSyncTime()),
            )
        }
        if (wasAuthenticated && !loggedIn) {
            initialRefreshTriggered = false
            updateCoordinator.reset()
        }
        val authenticatedAfterLaunch = !initialRefreshTriggered && loggedIn
        val becameAuthenticated = !wasAuthenticated && loggedIn
        if (authenticatedAfterLaunch || becameAuthenticated) {
            initialRefreshTriggered = true
            refreshNow()
        }
        if (loggedIn) {
            launchVersionCheckIfNeeded()
        }
    }

    private fun launchVersionCheckIfNeeded() {
        updateCoordinator.launchIfNeeded(viewModelScope)
    }

    private fun publishSyncOutcome(syncResult: SyncResult) {
        if (!syncResult.hadQueuedItems) return
        syncResult.events.forEach { event ->
            when (event) {
                is SyncEvent.ConflictSavedAsNew -> {
                    showSnackbar("Conflict detected: '${event.placeName}' saved as new item", "sync_conflict")
                }
                is SyncEvent.ItemFailed -> {
                    showSnackbar(formatItemFailureMessage(event), "sync_item_failed")
                }
            }
        }
        val summary = GeoVaultQueuedSyncMessageFormatter.format(
            outcome = GeoVaultQueuedSyncOutcome(
                successCount = syncResult.successCount,
                failedCount = syncResult.failedCount,
                conflictCount = syncResult.conflictCount,
            ),
            itemLabelSingular = "item",
            itemLabelPlural = "items",
        )
        val soleFailureDetail = syncResult.events
            .filterIsInstance<SyncEvent.ItemFailed>()
            .singleOrNull()
            ?.let { formatItemFailureMessage(it) }
        val message = when {
            syncResult.successCount == 0 && soleFailureDetail != null -> soleFailureDetail
            summary.isNotBlank() -> summary
            else -> return
        }
        showSnackbar(message, "sync_result")
    }

    private fun formatItemFailureMessage(event: SyncEvent.ItemFailed): String {
        val details = event.message?.takeIf { it.isNotBlank() }
        return when (event.reason) {
            SyncFailureReason.FetchFailed -> details ?: "Sync failed while checking server changes for '${event.placeName}'"
            SyncFailureReason.ConflictCreateFailed -> details ?: "Sync conflict for '${event.placeName}' could not be saved as new item"
            SyncFailureReason.UpdateFailed -> details ?: "Sync update failed for '${event.placeName}'"
            SyncFailureReason.CreateFailed -> details ?: "Sync create failed for '${event.placeName}'"
        }
    }

    private fun showSnackbar(message: String, prefix: String) {
        _state.update {
            it.copy(
                snackbar = GeoVaultSnackbarModel(
                    id = "${prefix}_${System.currentTimeMillis()}",
                    message = message,
                ),
            )
        }
    }

    private fun publishFromSnapshot(
        cached: List<Feature>,
        offline: List<OfflineFeature>,
        lastSyncMillis: Long,
    ) {
        val q = _state.value.searchQuery.trim()
        val filteredOffline = if (q.isBlank()) {
            offline
        } else {
            offline.filter { matchesQuery(it.feature, q) }
        }
        val filteredCached = if (q.isBlank()) {
            cached
        } else {
            cached.filter { matchesQuery(it, q) }
        }
        val offlineEditIds = filteredOffline.mapNotNull { it.feature.properties.database_id }.toSet()
        val saved = filteredCached.filter { it.properties.database_id !in offlineEditIds }

        _state.update {
            it.copy(
                saved = saved,
                offlineItems = filteredOffline,
                lastSyncMillis = lastSyncMillis,
                lastSyncLabel = formatLastSyncLabel(lastSyncMillis),
            )
        }
    }

    private fun matchesQuery(feature: Feature, query: String): Boolean {
        val name = feature.properties.name.orEmpty()
        val desc = feature.properties.description.orEmpty()
        return name.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
    }

    private fun formatLastSyncLabel(lastSyncMillis: Long): String {
        if (lastSyncMillis == 0L) return "Not synced"
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "Last synced: ${format.format(java.util.Date(lastSyncMillis))}"
    }
}

private enum class RefreshPhase {
    IDLE,
    FETCHING,
    SYNCING,
}

private const val TAG = "PlacesMainVm"

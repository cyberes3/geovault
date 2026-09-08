package com.geovault.places.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.geo.external.GeoVaultExternalMapLauncher
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.sync.GeoVaultQueuedSyncMessageFormatter
import com.geovault.common.sync.GeoVaultQueuedSyncOutcome
import com.geovault.common.sync.GeoVaultRefreshTimeoutPolicy
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.time.GeoVaultDateTimeFormat
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.places.BuildConfig
import com.geovault.places.PlacesApplication
import com.geovault.places.data.PlacesStore
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.domain.PlacesListSections
import com.geovault.places.domain.PlacesSyncEngine
import com.geovault.places.domain.PlacesSyncReport
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class PlacesShellState(
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val sections: PlacesListSections = PlacesListSections(emptyList(), emptyList()),
    val selectedKey: PlaceKey? = null,
    val lastSyncMillis: Long = 0L,
    val lastSyncLabel: String = "Not synced",
    val showSyncOverlay: Boolean = false,
    val syncOverlayTitle: String = "Syncing...",
    val syncOverlaySubtext: String = "Tap to cancel",
    val snackbar: GeoVaultSnackbarModel? = null,
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
    val mapLaunchArgs: PlacesMapLaunchArgs = PlacesMapLaunchArgs(),
)

data class PlacesMapLaunchArgs(
    val selectKey: PlaceKey? = null,
    val zoomLatitude: Double? = null,
    val zoomLongitude: Double? = null,
    val requestToken: Long = 0L,
)

class PlacesShellViewModel(
    application: Application,
    private val placesStore: PlacesStore,
    private val syncEngine: PlacesSyncEngine,
    private val trackNavigation: (Place) -> Unit,
    private val updatePromptBinding: GeoVaultAppUpdatePromptBinding,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as PlacesApplication).services,
    )

    constructor(application: Application, services: PlacesAppServices) : this(
        application,
        services.placesStore(),
        services.syncEngine(),
        { place -> services.navigationRepository().track(place) },
        GeoVaultAppUpdatePromptBinding(
            GeoVaultAndroidReleaseIdentity.Places.updateCoordinator(
                application = application,
                localFullCommitSha = { BuildConfig.GIT_COMMIT_SHA },
            )
        ),
    )

    private var refreshJob: Job? = null
    private var refreshCancelMessage: String? = null
    private var initialRefreshTriggered: Boolean = false
    private var isLoggedIn: Boolean = false
    private val mapLaunchEpoch = AtomicLong(0L)

    private val searchQuery = MutableStateFlow("")
    private val selectedKey = MutableStateFlow<PlaceKey?>(null)
    private val overlay = MutableStateFlow(OverlayBits())

    val state: StateFlow<PlacesShellState> = combine(
        placesStore.document,
        searchQuery,
        selectedKey,
        overlay,
    ) { document, query, key, bits ->
        val places = document.toDomain()
        val sections = PlacesListProjection.filter(places, query)
        val resolvedKey = PlacesMapStateTransforms.reconcileSelectedKey(
            PlacesListProjection.exportable(places),
            key,
        )
        PlacesShellState(
            isRefreshing = bits.isRefreshing,
            searchQuery = query,
            sections = sections,
            selectedKey = resolvedKey,
            lastSyncMillis = document.lastSyncMillis,
            lastSyncLabel = formatLastSyncLabel(document.lastSyncMillis),
            showSyncOverlay = bits.showSyncOverlay,
            syncOverlayTitle = bits.syncOverlayTitle,
            syncOverlaySubtext = bits.syncOverlaySubtext,
            snackbar = bits.snackbar,
            updateAvailable = bits.updateAvailable,
            mapLaunchArgs = bits.mapLaunchArgs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlacesShellState())

    init {
        updatePromptBinding.collect(viewModelScope) { prompt ->
            overlay.update { it.copy(updateAvailable = prompt) }
        }
    }

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        val wasAuthenticated = isLoggedIn
        val loggedIn = accountState.isLoggedIn
        isLoggedIn = loggedIn
        if (wasAuthenticated && !loggedIn) {
            initialRefreshTriggered = false
            updatePromptBinding.onSignedOut()
            refreshJob?.cancel(CancellationException("Signed out"))
        }
        val authenticatedAfterLaunch = !initialRefreshTriggered && loggedIn
        val becameAuthenticated = !wasAuthenticated && loggedIn
        if (authenticatedAfterLaunch || becameAuthenticated) {
            initialRefreshTriggered = true
            refreshNow()
        }
        if (loggedIn) {
            updatePromptBinding.onAuthenticated(viewModelScope)
        }
    }

    fun onSearchChanged(query: String) {
        searchQuery.value = query
    }

    fun setSelectedKey(key: PlaceKey?) {
        selectedKey.value = key
    }

    fun refreshNow(
        statusText: String = "Syncing...",
        tapHintText: String = "Tap to cancel",
    ) {
        if (refreshJob?.isActive == true || overlay.value.isRefreshing) {
            GeoVaultCaptureLog.i(TAG, "refreshNow ignored: already refreshing")
            return
        }
        refreshCancelMessage = null
        GeoVaultCaptureLog.i(TAG, "refreshNow start queued=${placesStore.places().count { it.isPending }}")
        refreshJob = viewModelScope.launch {
            overlay.update {
                it.copy(
                    isRefreshing = true,
                    showSyncOverlay = true,
                    syncOverlayTitle = statusText,
                    syncOverlaySubtext = tapHintText,
                )
            }
            try {
                val report = withTimeout(GeoVaultRefreshTimeoutPolicy.DEFAULT_TIMEOUT_MS) {
                    syncEngine.sync()
                }
                publishSyncOutcome(report)
                report.warningMessage?.let { showSnackbar(it, "main_warning") }
            } catch (_: TimeoutCancellationException) {
                GeoVaultCaptureLog.e(TAG, "refreshNow timed out")
                showSnackbar("Refresh timed out (10s)", "refresh_timeout")
            } catch (_: CancellationException) {
                val message = refreshCancelMessage ?: "Syncing cancelled"
                GeoVaultCaptureLog.w(TAG, "refreshNow cancelled: $message")
                showSnackbar(message, "refresh_cancelled")
            } finally {
                overlay.update {
                    it.copy(
                        isRefreshing = false,
                        showSyncOverlay = false,
                        syncOverlayTitle = statusText,
                        syncOverlaySubtext = tapHintText,
                    )
                }
            }
        }
    }

    fun cancelRefresh() {
        refreshCancelMessage = PlacesOfflineBehaviorPolicy.REFRESH_CANCELLED_USING_CACHE_MESSAGE
        refreshJob?.cancel(CancellationException(refreshCancelMessage))
    }

    fun clearSnackbar() {
        overlay.update { it.copy(snackbar = null) }
    }

    fun clearUpdateAvailable() {
        updatePromptBinding.dismissPrompt()
    }

    fun showExternalError(message: String) {
        showSnackbar(message, "external_error")
    }

    fun showSnackbar(message: String, prefix: String = "notice") {
        overlay.update {
            it.copy(
                snackbar = GeoVaultSnackbarModel(
                    id = "${prefix}_${System.currentTimeMillis()}",
                    message = message,
                ),
            )
        }
    }

    fun openMapToPlace(place: Place) {
        selectedKey.value = place.key
        overlay.update {
            it.copy(
                mapLaunchArgs = PlacesMapLaunchArgs(
                    selectKey = place.key,
                    zoomLatitude = place.content.location.latitude,
                    zoomLongitude = place.content.location.longitude,
                    requestToken = mapLaunchEpoch.incrementAndGet(),
                ),
            )
        }
    }

    fun onMapLaunchArgsConsumed() {
        val current = overlay.value.mapLaunchArgs
        overlay.update {
            it.copy(mapLaunchArgs = PlacesMapLaunchArgs(requestToken = current.requestToken))
        }
    }

    fun navigateToPlace(context: Context, place: Place) {
        val location = place.content.location
        val opened = GeoVaultExternalMapLauncher.open(
            context = context,
            latitude = location.latitude,
            longitude = location.longitude,
            label = place.content.name,
            onUnavailable = {
                showExternalError(PlacesOfflineBehaviorPolicy.MAP_APP_UNAVAILABLE_MESSAGE)
            },
        )
        if (opened) {
            trackNavigation(place)
        }
    }

    private fun publishSyncOutcome(report: PlacesSyncReport) {
        if (!report.hadQueuedItems) return
        val summary = GeoVaultQueuedSyncMessageFormatter.format(
            outcome = GeoVaultQueuedSyncOutcome(
                successCount = report.successCount,
                failedCount = report.failedCount,
                conflictCount = report.conflictCount,
            ),
            itemLabelSingular = "item",
            itemLabelPlural = "items",
        )
        if (summary.isBlank()) return
        showSnackbar(summary, "sync_result")
    }

    private fun formatLastSyncLabel(lastSyncMillis: Long): String {
        if (lastSyncMillis == 0L) return "Not synced"
        return "Last synced: ${GeoVaultDateTimeFormat.formatLocalTime(lastSyncMillis)}"
    }

    companion object {
        fun factory(services: PlacesAppServices): ViewModelProvider.Factory = Factory(services)
    }

    private class Factory(
        private val services: PlacesAppServices,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("PlacesShellViewModel.factory requires an Application")
            return modelClass.cast(PlacesShellViewModel(application, services))
                ?: error("Unknown ViewModel class ${modelClass.name}")
        }
    }
}

private data class OverlayBits(
    val isRefreshing: Boolean = false,
    val showSyncOverlay: Boolean = false,
    val syncOverlayTitle: String = "Syncing...",
    val syncOverlaySubtext: String = "Tap to cancel",
    val snackbar: GeoVaultSnackbarModel? = null,
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
    val mapLaunchArgs: PlacesMapLaunchArgs = PlacesMapLaunchArgs(),
)

private const val TAG = "PlacesMainVm"

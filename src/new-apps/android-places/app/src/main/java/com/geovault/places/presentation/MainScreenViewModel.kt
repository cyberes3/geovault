package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val saved: List<Feature> = emptyList(),
    val offline: List<Feature> = emptyList(),
    val lastSyncMillis: Long = 0L,
    val snackbar: GeoVaultSnackbarModel? = null,
)

class MainScreenViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val services = PlacesAppServices.from(application)
    private val cache = services.cacheStore()
    private val repository = services.placesRepository()
    private val syncUseCase = services.syncOfflinePlacesUseCase()
    private val authController = services.initialAuthController()

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    fun initialize() {
        refreshAuthAndCache()
    }

    fun onHostResumed() {
        refreshAuthAndCache()
    }

    fun onSearchChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        publishFromCache()
    }

    fun onAuthServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connectAuth() {
        _state.update {
            it.copy(
                isConnecting = true,
                snackbar = GeoVaultSnackbarModel(
                    id = "connect_start_${System.currentTimeMillis()}",
                    message = "Connecting to server..."
                )
            )
        }
        viewModelScope.launch {
            when (val result = authController.prepareOAuthConnection(_state.value.serverUrl)) {
                is CommonInitialAuthController.OAuthPreparationResult.Ready -> {
                    _state.update {
                        it.copy(
                            serverUrl = authController.getConfiguredServerUrlOrPeerDefault(),
                            oauthUrl = result.oauthUrl,
                            snackbar = null,
                        )
                    }
                }

                is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl -> {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            snackbar = GeoVaultSnackbarModel(
                                id = "connect_error_${System.currentTimeMillis()}",
                                message = result.message
                            )
                        )
                    }
                }

                is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer -> {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            snackbar = GeoVaultSnackbarModel(
                                id = "connect_error_${System.currentTimeMillis()}",
                                message = result.message
                            )
                        )
                    }
                }
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.update { it.copy(oauthUrl = null, isConnecting = false) }
    }

    fun refreshNow() {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val result = withContext(Dispatchers.IO) { repository.fetchPlaces() }
            result.onSuccess { collection ->
                cache.setCached(collection)
                withContext(Dispatchers.IO) { syncUseCase.runSync() }
                publishFromCache()
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        snackbar = GeoVaultSnackbarModel(
                            id = "main_error_${System.currentTimeMillis()}",
                            message = "Network failed: ${err.message ?: "Unknown error"}"
                        )
                    )
                }
            }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun saveOffline(feature: Feature, original: Feature? = null, offlineIndex: Int = -1) {
        cache.addOrUpdateOffline(feature, original, offlineIndex)
        publishFromCache()
        _state.update {
            it.copy(
                snackbar = GeoVaultSnackbarModel(
                    id = "offline_saved_${System.currentTimeMillis()}",
                    message = "Saved offline. Pull to sync."
                )
            )
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    private fun refreshAuthAndCache() {
        val serverUrl = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = serverUrl.isNotBlank() && authController.isLoggedIn()
        _state.update {
            it.copy(
                serverUrl = serverUrl,
                isAuthenticated = loggedIn,
                isConnecting = if (loggedIn) false else it.isConnecting,
                oauthUrl = if (loggedIn) null else it.oauthUrl,
                lastSyncMillis = cache.getLastSyncTime(),
            )
        }
        publishFromCache()
    }

    private fun publishFromCache() {
        val q = _state.value.searchQuery.trim()
        val cached = cache.getCachedFeatures()
        val offline = cache.getOfflineFeatures()

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
                offline = filteredOffline.map { item -> item.feature },
                lastSyncMillis = cache.getLastSyncTime(),
            )
        }
    }

    private fun matchesQuery(feature: Feature, query: String): Boolean {
        val name = feature.properties.name.orEmpty()
        val desc = feature.properties.description.orEmpty()
        return name.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
    }
}

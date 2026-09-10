package com.geovault.uploader.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.auth.GeoVaultConnectionStatus
import com.geovault.common.auth.GeoVaultConnectionValidator
import com.geovault.common.ui.model.GeoVaultActionRenderModel
import com.geovault.common.ui.model.GeoVaultStatusRenderModel
import com.geovault.common.ui.model.GeoVaultStatusVisualState
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.uploader.di.UploaderAppServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UploaderDestination {
    data object Home : UploaderDestination
    data object Queue : UploaderDestination
}

data class MainScreenState(
    val destination: UploaderDestination = UploaderDestination.Home,
    val status: GeoVaultStatusRenderModel = MainScreenViewModel.notConfiguredStatus(),
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
)

class MainScreenViewModel(
    application: Application,
    private val connectionValidator: GeoVaultConnectionValidator,
    updatePromptBinding: GeoVaultAppUpdatePromptBinding,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application),
    )

    constructor(application: Application, services: UploaderAppServices) : this(
        application,
        services.connectionValidator(),
        GeoVaultAppUpdatePromptBinding(services.updateCoordinator()),
    )

    private val updatePromptBinding = updatePromptBinding

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    private var validateJob: Job? = null
    private var isLoggedIn: Boolean = false

    init {
        this.updatePromptBinding.collect(viewModelScope) { prompt ->
            _state.update { it.copy(updateAvailable = prompt) }
        }
    }

    fun showHome() {
        _state.update { it.copy(destination = UploaderDestination.Home) }
    }

    fun showQueue() {
        _state.update { it.copy(destination = UploaderDestination.Queue) }
    }

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        val wasAuthenticated = isLoggedIn
        isLoggedIn = accountState.isLoggedIn
        if (wasAuthenticated && !isLoggedIn) {
            updatePromptBinding.onSignedOut()
            _state.update { it.copy(status = notConfiguredStatus()) }
        }
        if (!wasAuthenticated && isLoggedIn) {
            validate()
            updatePromptBinding.onAuthenticated(viewModelScope)
        }
    }

    fun clearUpdateAvailable() {
        updatePromptBinding.dismissPrompt()
    }

    fun validate() {
        if (validateJob?.isActive == true) return
        validateJob = viewModelScope.launch {
            try {
                _state.update { it.copy(status = loadingStatus()) }
                val result = connectionValidator.validate()
                _state.update { it.copy(status = toStatusModel(result)) }
            } finally {
                validateJob = null
            }
        }
    }

    companion object {
        fun notConfiguredStatus(): GeoVaultStatusRenderModel {
            return GeoVaultStatusRenderModel(
                visualState = GeoVaultStatusVisualState.Info,
                title = "Configuration Required",
                message = "Please configure settings first",
                secondaryAction = GeoVaultActionRenderModel(label = "Settings"),
            )
        }

        fun loadingStatus(): GeoVaultStatusRenderModel {
            return GeoVaultStatusRenderModel(
                visualState = GeoVaultStatusVisualState.Loading,
                title = "Validating Connection",
                message = "Connecting to server…",
            )
        }

        fun toStatusModel(status: GeoVaultConnectionStatus): GeoVaultStatusRenderModel {
            return when (status) {
                GeoVaultConnectionStatus.NotConfigured -> notConfiguredStatus()
                is GeoVaultConnectionStatus.Connected -> GeoVaultStatusRenderModel(
                    visualState = GeoVaultStatusVisualState.Success,
                    title = null,
                    message = UploaderMessageFormatter.validationConnected(),
                    primaryAction = GeoVaultActionRenderModel(label = "Choose File"),
                )
                GeoVaultConnectionStatus.Unauthorized -> GeoVaultStatusRenderModel(
                    visualState = GeoVaultStatusVisualState.Error,
                    title = "Validation Failed",
                    message = UploaderMessageFormatter.validationUnauthorized(),
                    secondaryAction = GeoVaultActionRenderModel(label = "Settings"),
                )
                GeoVaultConnectionStatus.Unreachable -> GeoVaultStatusRenderModel(
                    visualState = GeoVaultStatusVisualState.Error,
                    title = "Validation Failed",
                    message = UploaderMessageFormatter.validationConnectionFailed(
                        "Could not reach server",
                    ),
                    secondaryAction = GeoVaultActionRenderModel(label = "Settings"),
                )
            }
        }
    }
}

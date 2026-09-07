package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.uploader.BuildConfig
import com.geovault.uploader.MainActivity
import com.geovault.uploader.data.ValidationOutcome
import com.geovault.uploader.di.UploaderAppServices
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeScreenState(
    val validationTitle: String = "Configuration Required",
    val validationMessage: String = "Please configure settings first",
    val isValidationLoading: Boolean = false,
    val validationOutcome: ValidationOutcome = ValidationOutcome.Info,
    val importantSnackbar: GeoVaultSnackbarModel? = null,
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null
)

class HomeViewModel(
    application: Application,
    services: UploaderAppServices,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application)
    )

    private val validationRepository = services.validationRepository
    private val updatePromptBinding = GeoVaultAppUpdatePromptBinding(services.updateCoordinator())

    private val _state = MutableStateFlow(HomeScreenState())
    val state: StateFlow<HomeScreenState> = _state.asStateFlow()

    private var validateJob: Job? = null
    private var isLoggedIn: Boolean = false

    init {
        updatePromptBinding.collect(viewModelScope) { prompt ->
            _state.update { it.copy(updateAvailable = prompt) }
        }
    }

    fun initialize(intent: Intent?) = Unit

    fun onHostResumed() = Unit

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        val wasAuthenticated = isLoggedIn
        isLoggedIn = accountState.isLoggedIn
        if (wasAuthenticated && !isLoggedIn) {
            updatePromptBinding.onSignedOut()
        }
        if (!wasAuthenticated && isLoggedIn) {
            _state.update { it.copy(importantSnackbar = null) }
            validate()
            launchVersionCheckIfNeeded()
        }
    }

    fun clearImportantMessage() {
        _state.update { it.copy(importantSnackbar = null) }
    }

    fun clearUpdateAvailable() {
        updatePromptBinding.dismissPrompt()
    }

    fun validate() {
        if (validateJob?.isActive == true) return
        validateJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        validationTitle = "Validating API Key…",
                        validationMessage = "Connecting to server…",
                        isValidationLoading = true,
                        validationOutcome = ValidationOutcome.Loading
                    )
                }
                val result = validationRepository.validateConnection()
                _state.update {
                    it.copy(
                        validationTitle = result.title,
                        validationMessage = result.message,
                        isValidationLoading = false,
                        validationOutcome = result.outcome
                    )
                }
            } finally {
                validateJob = null
            }
        }
    }

    private fun launchVersionCheckIfNeeded() {
        updatePromptBinding.onAuthenticated(viewModelScope)
    }

    private fun newImportantId(): String = UUID.randomUUID().toString()
}

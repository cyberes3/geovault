package com.geovault.common.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.StateFlow

class GeoVaultAccountViewModel(
    application: Application,
    authController: CommonInitialAuthController,
) : AndroidViewModel(application) {
    private val account = GeoVaultAccountController(
        scope = viewModelScope,
        appContext = application.applicationContext,
        authController = authController,
    )

    val state: StateFlow<GeoVaultAccountUiState> = account.state

    fun initialize() = account.initialize()
    fun onHostResumed() = account.onHostResumed()
    fun onServerUrlChanged(url: String) = account.onServerUrlChanged(url)
    fun connect() = account.connect()
    fun onOauthUrlConsumed() = account.onOauthUrlConsumed()
    fun showExternalError(message: String) = account.showExternalError(message)
    fun clearInfoMessage() = account.clearInfoMessage()
    fun disconnect(mainActivityClass: Class<*>) = account.disconnect(mainActivityClass)

    companion object {
        fun factory(authController: CommonInitialAuthController): ViewModelProvider.Factory {
            return Factory(authController)
        }
    }

    private class Factory(
        private val authController: CommonInitialAuthController,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("GeoVaultAccountViewModel.factory requires an Application")
            return modelClass.cast(GeoVaultAccountViewModel(application, authController))
                ?: error("Unknown ViewModel class ${modelClass.name}")
        }
    }
}

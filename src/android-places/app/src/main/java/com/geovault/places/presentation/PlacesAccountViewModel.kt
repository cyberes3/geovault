package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.GeoVaultAccountController
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.places.di.PlacesAppServices
import kotlinx.coroutines.flow.StateFlow

class PlacesAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val account = GeoVaultAccountController(
        scope = viewModelScope,
        appContext = application.applicationContext,
        authController = PlacesAppServices.from(application).initialAuthController(),
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
}

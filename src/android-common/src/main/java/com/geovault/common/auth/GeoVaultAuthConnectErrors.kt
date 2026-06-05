package com.geovault.common.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connect/OAuth-prep errors for [com.geovault.common.ui.components.GeoVaultInitialAuthView].
 * Producers ([GeoVaultAccountController], app auth ViewModels) publish here; the auth view
 * shows a snackbar without each screen passing error props.
 */
object GeoVaultAuthConnectErrors {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var onClearListener: (() -> Unit)? = null

    fun setOnClearListener(listener: (() -> Unit)?) {
        onClearListener = listener
    }

    fun show(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            clear(notifyListener = false)
            return
        }
        _message.value = trimmed
    }

    fun clear(notifyListener: Boolean = true) {
        _message.value = null
        if (notifyListener) {
            onClearListener?.invoke()
        }
    }
}

fun CommonInitialAuthController.OAuthPreparationResult.publishConnectErrorIfNeeded() {
    when (this) {
        is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl ->
            GeoVaultAuthConnectErrors.show(message)
        is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer ->
            GeoVaultAuthConnectErrors.show(message)
        is CommonInitialAuthController.OAuthPreparationResult.Ready -> Unit
    }
}

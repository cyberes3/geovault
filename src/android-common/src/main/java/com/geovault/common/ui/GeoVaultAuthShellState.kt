package com.geovault.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.components.GeoVaultAuthExtraAction

/**
 * Auth fields shared by tab chrome and the sign-in gate.
 *
 * Pass this object instead of threading the individual connect/settings callbacks through every
 * tab composable.
 */
@Immutable
data class GeoVaultAuthShellState(
    val isAuthenticated: Boolean,
    val serverUrl: String,
    val onServerUrlChanged: (String) -> Unit,
    val onConnect: () -> Unit,
    val onOpenSettings: () -> Unit,
    val isConnecting: Boolean = false,
    val connectButtonTooltip: String? = null,
    val extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
)

@Composable
fun rememberGeoVaultAuthShellState(
    accountState: GeoVaultAccountUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    connectButtonTooltip: String? = null,
    extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
): GeoVaultAuthShellState {
    return remember(
        accountState.isLoggedIn,
        accountState.serverUrl,
        accountState.isConnecting,
        onServerUrlChanged,
        onConnect,
        onOpenSettings,
        connectButtonTooltip,
        extraActions,
    ) {
        GeoVaultAuthShellState(
            isAuthenticated = accountState.isLoggedIn,
            serverUrl = accountState.serverUrl,
            onServerUrlChanged = onServerUrlChanged,
            onConnect = onConnect,
            onOpenSettings = onOpenSettings,
            isConnecting = accountState.isConnecting,
            connectButtonTooltip = connectButtonTooltip,
            extraActions = extraActions,
        )
    }
}

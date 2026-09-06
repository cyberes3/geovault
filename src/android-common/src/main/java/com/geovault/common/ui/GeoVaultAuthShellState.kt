package com.geovault.common.ui

import androidx.compose.runtime.Immutable
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

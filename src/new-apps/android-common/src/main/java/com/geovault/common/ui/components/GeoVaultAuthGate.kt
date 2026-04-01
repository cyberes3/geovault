package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun GeoVaultAuthGate(
    isAuthenticated: Boolean,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
    authenticatedContent: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        if (isAuthenticated) {
            authenticatedContent()
        } else {
            GeoVaultInitialAuthView(
                serverUrl = serverUrl,
                onServerUrlChanged = onServerUrlChanged,
                onConnect = onConnect,
                isConnecting = isConnecting,
                extraActions = extraActions,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

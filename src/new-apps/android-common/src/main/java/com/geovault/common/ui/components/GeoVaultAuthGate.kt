package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Wraps content when signed in; otherwise shows [GeoVaultInitialAuthView] (see that composable for connect UX). */
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
            GeoVaultRequestBottomTabsHidden(shouldHide = true)
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

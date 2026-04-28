package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.geovault.common.ui.modifier.geoVaultStableNavigationBarsPadding

/** Wraps content when signed in; otherwise shows [GeoVaultInitialAuthView] (see that composable for connect UX). */
@Composable
fun GeoVaultAuthGate(
    isAuthenticated: Boolean,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    connectButtonTooltip: String? = null,
    extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
    authenticatedContent: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        if (isAuthenticated) {
            authenticatedContent()
        } else {
            GeoVaultRequestBottomTabsHidden(shouldHide = true)
            // The sign-in form can be the only thing on screen (e.g. NGS / Survey before
            // first authentication) so it owns its own nav-bar safe-area; the surrounding
            // theme is intentionally inset-agnostic.
            GeoVaultInitialAuthView(
                serverUrl = serverUrl,
                onServerUrlChanged = onServerUrlChanged,
                onConnect = onConnect,
                isConnecting = isConnecting,
                connectButtonTooltip = connectButtonTooltip,
                extraActions = extraActions,
                modifier = Modifier
                    .fillMaxSize()
                    .geoVaultStableNavigationBarsPadding()
            )
        }
    }
}

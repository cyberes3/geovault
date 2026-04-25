package com.geovault.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
internal fun TrackerTabPlaceholderScreen(
    title: String,
    placeholderText: String,
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
    settingsOverflowTooltip: String? = null,
    connectButtonTooltip: String? = null,
    authenticatedMainContent: (@Composable ColumnScope.() -> Unit)? = null,
    authenticatedFooter: (@Composable () -> Unit)? = null,
    authenticatedFloatingAction: (@Composable BoxScope.() -> Unit)? = null,
    scrollAuthenticatedMainContent: Boolean = true,
    authenticatedContentHorizontalPadding: Dp = 16.dp,
    authenticatedBottomSpacer: Dp = 16.dp,
    settingsMenuEnabled: Boolean = true,
    /**
     * When true, the tab-level [GeoVaultTopTitleBar] is omitted so a full-screen sub-view
     * (its own scaffold / dismiss title bar) is not stacked under the tab title.
     */
    suppressTabTopBar: Boolean = false,
) {
    Scaffold(
        topBar = {
            if (!suppressTabTopBar) {
                GeoVaultTopTitleBar(
                    title = title,
                    actionsContent = {
                        GeoVaultTopBarSettingsMenuAction(
                            onOpenSettings = onOpenSettings,
                            enabled = settingsMenuEnabled,
                            overflowTooltip = settingsOverflowTooltip,
                        )
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GeoVaultColorTokens.ListBackground),
        ) {
            GeoVaultAuthGate(
                isAuthenticated = isAuthenticated,
                serverUrl = serverUrl,
                onServerUrlChanged = onAuthServerUrlChanged,
                onConnect = onAuthConnect,
                isConnecting = isConnecting,
                connectButtonTooltip = connectButtonTooltip,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (authenticatedMainContent != null) {
                            val mainModifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = authenticatedContentHorizontalPadding)
                                .then(
                                    if (scrollAuthenticatedMainContent) {
                                        Modifier.verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    },
                                )
                            Column(modifier = mainModifier) {
                                authenticatedMainContent()
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(placeholderText)
                            }
                        }
                    }
                    authenticatedFooter?.invoke()
                    Spacer(modifier = Modifier.height(authenticatedBottomSpacer))
                }
            }
            if (isAuthenticated && authenticatedFloatingAction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = authenticatedContentHorizontalPadding),
                    content = authenticatedFloatingAction,
                )
            }
            TrackerParamsOverlayLayer()
        }
    }
}

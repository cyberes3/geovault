package com.geovault.common.ui

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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.TopBarMenuEntry
import com.geovault.common.ui.modifier.geoVaultStableNavigationBarsPadding

/**
 * Branded [GeoVaultTopTitleBar], auth gate, and a weighted main column.
 *
 * Optional [tabOverlay] is drawn above [authenticatedMainContent] inside the same padded body —
 * the slot for sub-views that should leave the outer title bar in place. Each such sub-view
 * should render through [com.geovault.common.ui.components.GeoVaultSubViewScaffold].
 */
@Composable
fun GeoVaultTabShell(
    title: String,
    auth: GeoVaultAuthShellState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    placeholderText: String = "",
    settingsOverflowTooltip: String? = null,
    extraTopBarEntries: List<TopBarMenuEntry> = emptyList(),
    settingsMenuVisibility: GeoVaultTopBarMenuVisibility = GeoVaultTopBarMenuVisibility.AuthenticatedOnly,
    settingsMenuEnabled: Boolean = true,
    applyNavigationBarsPadding: Boolean = false,
    authenticatedMainContent: (@Composable ColumnScope.() -> Unit)? = null,
    authenticatedFooter: (@Composable () -> Unit)? = null,
    authenticatedFloatingAction: (@Composable BoxScope.() -> Unit)? = null,
    scrollAuthenticatedMainContent: Boolean = true,
    authenticatedContentHorizontalPadding: Dp = 16.dp,
    authenticatedBottomSpacer: Dp = 16.dp,
    tabOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            GeoVaultTopTitleBar(
                title = title,
                subtitle = subtitle,
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = auth.onOpenSettings,
                        extraEntries = extraTopBarEntries,
                        visibility = settingsMenuVisibility,
                        enabled = settingsMenuEnabled,
                        overflowTooltip = settingsOverflowTooltip,
                    )
                },
            )
        },
    ) { padding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colors.background)
            .then(
                if (applyNavigationBarsPadding) {
                    Modifier.geoVaultStableNavigationBarsPadding()
                } else {
                    Modifier
                },
            )
        Box(modifier = bodyModifier) {
            GeoVaultAuthGate(
                isAuthenticated = auth.isAuthenticated,
                serverUrl = auth.serverUrl,
                onServerUrlChanged = auth.onServerUrlChanged,
                onConnect = auth.onConnect,
                isConnecting = auth.isConnecting,
                connectButtonTooltip = auth.connectButtonTooltip,
                extraActions = auth.extraActions,
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
                        } else if (placeholderText.isNotEmpty()) {
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
            if (auth.isAuthenticated && authenticatedFloatingAction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = authenticatedContentHorizontalPadding),
                    content = authenticatedFloatingAction,
                )
            }
            if (tabOverlay != null) {
                tabOverlay()
            }
        }
    }
}

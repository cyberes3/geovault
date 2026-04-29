package com.geovault.common.ui.components

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
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Tracker-style tab body: branded [GeoVaultTopTitleBar] (always visible), auth gate, and a
 * weighted main column. Optional [tabOverlay] is drawn above [authenticatedMainContent]
 * inside the same padded body — this is the slot for sub-views that should leave the
 * outer title bar in place (tracker params, editor sub-views, group actions, etc.). Each
 * such sub-view should render through [GeoVaultSubViewScaffold] so it gets the standard
 * compact "<-Title  X" dismiss strip stacked under this bar (same model as the survey
 * shell). The host's title bar is never swapped — there is no branded-take-over path.
 */
@Composable
fun GeoVaultNavTabShell(
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
    tabOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
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
            if (tabOverlay != null) {
                tabOverlay()
            }
        }
    }
}

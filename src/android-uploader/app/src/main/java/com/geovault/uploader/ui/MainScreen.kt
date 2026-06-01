package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultStatusPane
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.theme.GeoVaultLayoutTokens
import com.geovault.common.ui.update.GeoVaultUpdateAvailableSnackbarHost
import com.geovault.uploader.presentation.HomeScreenState
import com.geovault.uploader.R

@Composable
fun MainScreen(
    state: HomeScreenState,
    onOpenSettings: () -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onChooseFileClick: () -> Unit,
    onDismissImportant: () -> Unit,
    onDismissUpdateAvailable: () -> Unit
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "GeoVault Uploader",
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        visibility = GeoVaultTopBarMenuVisibility.Always,
                        overflowTooltip = stringResource(R.string.tooltip_nav_settings),
                    )
                }
            )
        }
    ) { padding ->
        GeoVaultAuthGate(
            isAuthenticated = state.isAuthenticated,
            serverUrl = state.serverUrl,
            onServerUrlChanged = onAuthServerUrlChanged,
            onConnect = onAuthConnect,
            isConnecting = state.isConnecting,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(GeoVaultLayoutTokens.ScreenPadding)
                ) {
                    GeoVaultStatusPane(
                        model = MainScreenStatusMapper.toValidationStatusModel(state),
                        onPrimaryActionClick = onChooseFileClick,
                        onSecondaryActionClick = onOpenSettings
                    )
                }
                GeoVaultSnackbarHost(
                    model = state.importantSnackbar,
                    onDismiss = onDismissImportant,
                    onAction = { },
                )
                GeoVaultUpdateAvailableSnackbarHost(
                    update = state.updateAvailable,
                    onDismiss = onDismissUpdateAvailable,
                    stackBottomInset = if (state.importantSnackbar != null) 72.dp else 0.dp,
                )
            }
        }
    }
}

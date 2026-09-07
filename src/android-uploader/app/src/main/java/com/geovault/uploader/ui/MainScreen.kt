package com.geovault.uploader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultStatusPane
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.uploader.R
import com.geovault.uploader.presentation.MainScreenState

@Composable
fun MainScreen(
    state: MainScreenState,
    auth: GeoVaultAuthShellState,
    onChooseFileClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GeoVaultTabShell(
        title = stringResource(R.string.app_title),
        auth = auth,
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        settingsMenuVisibility = GeoVaultTopBarMenuVisibility.Always,
        applyNavigationBarsPadding = true,
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            GeoVaultStatusPane(
                model = state.status,
                onPrimaryActionClick = onChooseFileClick,
                onSecondaryActionClick = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

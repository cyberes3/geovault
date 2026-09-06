package com.geovault.uploader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultStatusPane
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.common.ui.theme.GeoVaultLayoutTokens
import com.geovault.uploader.presentation.HomeScreenState
import com.geovault.uploader.R

@Composable
fun MainScreen(
    state: HomeScreenState,
    auth: GeoVaultAuthShellState,
    onChooseFileClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GeoVaultTabShell(
        title = "GeoVault Uploader",
        auth = auth,
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        settingsMenuVisibility = GeoVaultTopBarMenuVisibility.Always,
        applyNavigationBarsPadding = true,
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            GeoVaultStatusPane(
                model = MainScreenStatusMapper.toValidationStatusModel(state),
                onPrimaryActionClick = onChooseFileClick,
                onSecondaryActionClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(GeoVaultLayoutTokens.ScreenPadding),
            )
        },
    )
}

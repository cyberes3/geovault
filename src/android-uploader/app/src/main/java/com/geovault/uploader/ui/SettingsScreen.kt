package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.components.GeoVaultAccountSettingsSection
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.theme.GeoVaultLayoutTokens
import com.geovault.uploader.presentation.SettingsState

@Composable
fun SettingsScreen(
    state: SettingsState,
    accountState: GeoVaultAccountUiState,
    onServerUrlChanged: (String) -> Unit,
    onSuffixChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .then(
                if (accountState.isLoggedIn) Modifier.padding(GeoVaultLayoutTokens.ScreenPadding) else Modifier
            )
    ) {
        GeoVaultAccountSettingsSection(
            accountState = accountState,
            onServerUrlChanged = onServerUrlChanged,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            connectTitle = "Connect Account",
            connectHelpText = "Enter your GeoVault server URL and connect your account.",
            signedInPrefix = {
                GeoVaultToggleHelpCard(
                    checked = state.suffixEnabled,
                    onCheckedChange = onSuffixChanged,
                    title = "Add Android Upload Suffix",
                    helpText = "Append '_android_upload' to uploaded filenames."
                )
                Spacer(modifier = Modifier.height(16.dp))
            },
        )
    }
}

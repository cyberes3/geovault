package com.geovault.uploader.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.components.GeoVaultAccountOnlySettingsContent
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.uploader.R
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
    GeoVaultAccountOnlySettingsContent(
        accountState = accountState,
        onServerUrlChanged = onServerUrlChanged,
        onConnect = onConnect,
        onDisconnect = onDisconnect,
        modifier = modifier,
        contentPadding = contentPadding,
        connectTitle = stringResource(R.string.connect_account),
        connectHelpText = stringResource(R.string.connect_account_help),
        signedInPrefix = {
            GeoVaultToggleHelpCard(
                checked = state.suffixEnabled,
                onCheckedChange = onSuffixChanged,
                title = stringResource(R.string.add_android_upload_suffix),
                helpText = stringResource(R.string.add_android_upload_suffix_help),
            )
            Spacer(modifier = Modifier.height(16.dp))
        },
    )
}

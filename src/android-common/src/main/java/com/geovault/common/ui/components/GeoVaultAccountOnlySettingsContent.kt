package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.theme.GeoVaultLayoutTokens

@Composable
fun GeoVaultAccountOnlySettingsContent(
    accountState: GeoVaultAccountUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    signedInPrefix: (@Composable ColumnScope.() -> Unit)? = null,
    connectButtonTooltip: String? = null,
    connectTitle: String? = null,
    connectHelpText: String? = null,
    serverUrlLabel: String? = null,
    connectButtonText: String? = null,
    connectingButtonText: String? = null,
    serverBlockTitle: String? = null,
    disconnectButtonText: String? = null,
    disconnectButtonTooltip: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(GeoVaultLayoutTokens.ScreenPadding),
    ) {
        GeoVaultAccountSettingsSection(
            accountState = accountState,
            onServerUrlChanged = onServerUrlChanged,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            signedInPrefix = signedInPrefix,
            connectButtonTooltip = connectButtonTooltip,
            connectTitle = connectTitle,
            connectHelpText = connectHelpText,
            serverUrlLabel = serverUrlLabel,
            connectButtonText = connectButtonText,
            connectingButtonText = connectingButtonText,
            serverBlockTitle = serverBlockTitle,
            disconnectButtonText = disconnectButtonText,
            disconnectButtonTooltip = disconnectButtonTooltip,
        )
    }
}

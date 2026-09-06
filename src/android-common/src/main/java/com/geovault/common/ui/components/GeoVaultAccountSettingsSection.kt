package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor

/**
 * Signed-out connect form or signed-in server block for Settings overlays.
 *
 * Uses [GeoVaultAccountUiState.displayEmail] — do not parse [GeoVaultAccountUiState.loggedInText].
 */
@Composable
fun GeoVaultAccountSettingsSection(
    accountState: GeoVaultAccountUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    showSignedOutDivider: Boolean = false,
    signedOut: (@Composable ColumnScope.() -> Unit)? = null,
    signedInPrefix: (@Composable ColumnScope.() -> Unit)? = null,
    connectButtonTooltip: String? = null,
    connectTitle: String? = null,
    connectHelpText: String? = null,
    serverUrlLabel: String? = null,
    connectButtonText: String? = null,
    connectingButtonText: String? = null,
    captureOutsideTapAcrossParent: Boolean = true,
    serverBlockTitle: String? = null,
    disconnectButtonText: String? = null,
    disconnectButtonTooltip: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!accountState.isLoggedIn) {
            if (showSignedOutDivider) {
                Divider(
                    color = geoVaultHairlineDividerColor(),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (signedOut != null) {
                signedOut()
            } else {
                GeoVaultInitialAuthView(
                    serverUrl = accountState.serverUrl,
                    onServerUrlChanged = onServerUrlChanged,
                    onConnect = onConnect,
                    isConnecting = accountState.isConnecting,
                    title = connectTitle ?: "Connect Account",
                    helpText = connectHelpText
                        ?: "Enter your server URL and connect your account to continue.",
                    serverUrlLabel = serverUrlLabel ?: "Server URL",
                    connectButtonText = connectButtonText ?: "Connect Account",
                    connectingButtonText = connectingButtonText ?: "Connecting...",
                    connectButtonTooltip = connectButtonTooltip,
                    captureOutsideTapAcrossParent = captureOutsideTapAcrossParent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            signedInPrefix?.invoke(this)
            GeoVaultServerConfigBlock(
                serverUrl = accountState.serverUrl,
                loggedInEmail = accountState.displayEmail,
                onDisconnectConfirmed = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                title = serverBlockTitle ?: "GeoVault Server",
                disconnectButtonText = disconnectButtonText ?: "Disconnect",
                disconnectButtonTooltip = disconnectButtonTooltip,
            )
        }
        val accountInfoMessage = accountState.infoMessage
        if (!accountInfoMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(accountInfoMessage)
        }
    }
}

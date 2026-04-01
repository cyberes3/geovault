package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultServerConfigBlock
import com.geovault.common.ui.components.GeoVaultToggleHelpCard
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap
import com.geovault.uploader.presentation.SettingsState

@Composable
fun SettingsScreen(
    state: SettingsState,
    onServerUrlChanged: (String) -> Unit,
    onSuffixChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "Settings",
                hideIconActions = !state.isLoggedIn,
                rightActions = listOf(
                    GeoVaultTopTitleBarDefaults.closeAction(onClick = onClose)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .dismissKeyboardOnOutsideTap()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            if (!state.isLoggedIn) {
                GeoVaultInitialAuthView(
                    serverUrl = state.serverUrl,
                    onServerUrlChanged = onServerUrlChanged,
                    onConnect = onConnect,
                    title = "Connect Account",
                    helpText = "Enter your GeoVault server URL and connect your account.",
                    connectButtonText = "Connect Account",
                    connectingButtonText = "Connecting...",
                    isConnecting = state.isConnecting,
                    connectEnabled = true,
                    inputEnabled = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                GeoVaultToggleHelpCard(
                    checked = state.suffixEnabled,
                    onCheckedChange = onSuffixChanged,
                    title = "Add Android Upload Suffix",
                    helpText = "Append '_android_upload' to uploaded filenames."
                )
                Spacer(modifier = Modifier.height(16.dp))
                val loggedInEmail = state.loggedInText.removePrefix("Logged in as").trim().ifBlank { "Authenticated User" }
                GeoVaultServerConfigBlock(
                    serverUrl = state.serverUrl,
                    loggedInEmail = loggedInEmail,
                    onDisconnectConfirmed = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (!state.infoMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.infoMessage)
            }
        }
    }
}

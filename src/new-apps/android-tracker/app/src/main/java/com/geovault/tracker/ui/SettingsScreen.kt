package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultServerConfigBlock
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.tracker.presentation.SettingsState

@Composable
fun SettingsScreen(
    state: SettingsState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(title = "Settings")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            if (!state.isLoggedIn) {
                GeoVaultInitialAuthView(
                    serverUrl = state.serverUrl,
                    onServerUrlChanged = onServerUrlChanged,
                    onConnect = onConnect,
                    isConnecting = state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val email = state.loggedInText
                    .removePrefix("Logged in as").trim()
                    .ifBlank { "Authenticated User" }
                GeoVaultServerConfigBlock(
                    serverUrl = state.serverUrl,
                    loggedInEmail = email,
                    onDisconnectConfirmed = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.infoMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(state.infoMessage)
            }
        }
    }
}

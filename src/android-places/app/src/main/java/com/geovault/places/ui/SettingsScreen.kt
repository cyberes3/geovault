package com.geovault.places.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.ui.components.GeoVaultInitialAuthView
import com.geovault.common.ui.components.GeoVaultServerConfigBlock
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.places.R
import com.geovault.places.presentation.SettingsState

@Composable
fun SettingsScreen(
    state: SettingsState,
    accountState: GeoVaultAccountUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = stringResource(R.string.app_title_bar),
            )
        }
    ) { padding ->
        GeoVaultSubViewScaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            title = "Settings",
            onClose = onClose,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                if (!accountState.isLoggedIn) {
                    GeoVaultInitialAuthView(
                        serverUrl = accountState.serverUrl,
                        onServerUrlChanged = onServerUrlChanged,
                        onConnect = onConnect,
                        isConnecting = accountState.isConnecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val email = accountState.loggedInText.removePrefix("Logged in as").trim().ifBlank { "Authenticated User" }
                    GeoVaultServerConfigBlock(
                        serverUrl = accountState.serverUrl,
                        loggedInEmail = email,
                        onDisconnectConfirmed = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val accountInfoMessage = accountState.infoMessage
                if (!accountInfoMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(accountInfoMessage)
                }
            }
        }
    }
}

package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultServerConfigBlock(
    serverUrl: String,
    loggedInEmail: String,
    onDisconnectConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "GeoVault Server",
    disconnectButtonText: String = "Disconnect"
) {
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Divider(
            color = GeoVaultColorTokens.BorderLight,
            thickness = 1.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.subtitle1
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            GeoVaultInput(
                value = serverUrl,
                onValueChange = {},
                label = "Server URL",
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Logged in as $loggedInEmail",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        GeoVaultSecondaryButton(
            text = disconnectButtonText,
            onClick = { showDisconnectConfirm = true },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showDisconnectConfirm) {
        GeoVaultConfirmationDialog(
            title = "Disconnect Account?",
            message = "You will be signed out. You can connect again from Settings.",
            onConfirm = {
                showDisconnectConfirm = false
                onDisconnectConfirmed()
            },
            onCancel = { showDisconnectConfirm = false },
            confirmText = "Disconnect",
            cancelText = "Cancel"
        )
    }
}

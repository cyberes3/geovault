package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap
import com.geovault.common.ui.theme.GeoVaultLayoutTokens
import androidx.compose.ui.unit.dp

enum class GeoVaultAuthExtraActionStyle {
    PRIMARY,
    SECONDARY
}

data class GeoVaultAuthExtraAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val style: GeoVaultAuthExtraActionStyle = GeoVaultAuthExtraActionStyle.SECONDARY
)

@Composable
fun GeoVaultInitialAuthView(
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Connect Account",
    helpText: String = "Enter your server URL and connect your account to continue.",
    serverUrlLabel: String = "Server URL",
    connectButtonText: String = "Connect Account",
    connectingButtonText: String = "Connecting...",
    isConnecting: Boolean = false,
    connectEnabled: Boolean = true,
    inputEnabled: Boolean = true,
    extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
    captureOutsideTapAcrossParent: Boolean = true
) {
    val connectState = rememberConnectingButtonState(
        isConnecting = isConnecting,
        onConnect = onConnect,
    )

    val containerModifier = if (captureOutsideTapAcrossParent) {
        Modifier.fillMaxSize().then(modifier)
    } else {
        modifier
    }
    Box(
        modifier = containerModifier
            .dismissKeyboardOnOutsideTap()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(GeoVaultLayoutTokens.ScreenPadding),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(helpText)
            Spacer(modifier = Modifier.height(12.dp))
            GeoVaultInput(
                value = serverUrl,
                onValueChange = onServerUrlChanged,
                label = serverUrlLabel,
                enabled = inputEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            GeoVaultPrimaryButton(
                text = if (connectState.isEffectivelyConnecting) connectingButtonText else connectButtonText,
                onClick = { connectState.onClick() },
                enabled = connectEnabled && !connectState.isEffectivelyConnecting,
                modifier = Modifier.fillMaxWidth()
            )
            extraActions.forEach { action ->
                Spacer(modifier = Modifier.height(8.dp))
                when (action.style) {
                    GeoVaultAuthExtraActionStyle.PRIMARY -> {
                        GeoVaultPrimaryButton(
                            text = action.label,
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    GeoVaultAuthExtraActionStyle.SECONDARY -> {
                        GeoVaultSecondaryButton(
                            text = action.label,
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

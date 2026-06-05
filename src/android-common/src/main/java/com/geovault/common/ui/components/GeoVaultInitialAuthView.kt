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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.geovault.common.R
import com.geovault.common.auth.GeoVaultAuthConnectErrors
import com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
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

/**
 * Signed-out server URL entry and connect action.
 *
 * While [isConnecting] is true, the primary button shows [connectingButtonText] with disabled styling but remains
 * tappable so users can restart OAuth flow if needed. [isConnecting] should stay true from connect through
 * browser handoff until the host clears it on resume (see [com.geovault.common.auth.AuthConnectUiLifecycle]).
 *
 * Connect failures (invalid URL, unreachable server, timeout, OAuth callback errors) are published to
 * [GeoVaultAuthConnectErrors] and shown as a snackbar on this view. Callers should not duplicate those
 * messages at screen level.
 */
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
    connectButtonTooltip: String? = null,
    inputEnabled: Boolean = true,
    extraActions: List<GeoVaultAuthExtraAction> = emptyList(),
    captureOutsideTapAcrossParent: Boolean = true,
) {
    val connectErrorMessage by GeoVaultAuthConnectErrors.message.collectAsState()
    val connectState = rememberConnectingButtonState(
        isConnecting = isConnecting,
        onConnect = {
            GeoVaultAuthConnectErrors.clear()
            onConnect()
        },
    )
    val resolvedConnectTooltip = connectButtonTooltip
        ?: stringResource(R.string.gv_common_auth_connect_tooltip)

    val snackbarModel = remember(connectErrorMessage, connectState.isEffectivelyConnecting) {
        val message = connectErrorMessage?.trim().orEmpty()
        if (message.isNotBlank() && !connectState.isEffectivelyConnecting) {
            GeoVaultSnackbarModel(id = "auth_connect_error", message = message)
        } else {
            null
        }
    }

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
                enabled = connectEnabled,
                visuallyDisabled = connectState.isEffectivelyConnecting,
                tooltip = resolvedConnectTooltip,
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
        GeoVaultSnackbarHost(
            model = snackbarModel,
            onDismiss = { GeoVaultAuthConnectErrors.clear() },
            onAction = { GeoVaultAuthConnectErrors.clear() },
        )
    }
}

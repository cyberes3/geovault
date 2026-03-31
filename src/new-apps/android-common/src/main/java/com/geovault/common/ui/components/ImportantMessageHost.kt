package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import kotlinx.coroutines.delay

data class ImportantMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

@Composable
fun ImportantMessageHost(
    message: ImportantMessage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    var visible by remember(message.text, message.actionLabel) { mutableStateOf(true) }
    if (!visible) return

    LaunchedEffect(message.text, message.actionLabel) {
        delay(15_000L)
        visible = false
        onDismiss()
    }

    Row(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(16.dp)
            .background(GeoVaultColorTokens.SnackbarBackground)
            .clickable {
                visible = false
                onDismiss()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message.text,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (!message.actionLabel.isNullOrBlank() && message.onAction != null) {
            Text(
                text = message.actionLabel,
                color = GeoVaultColorTokens.SnackbarAction,
                modifier = Modifier.clickable {
                    message.onAction.invoke()
                    visible = false
                    onDismiss()
                }.padding(start = 12.dp)
            )
        }
    }
}

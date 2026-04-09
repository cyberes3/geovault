package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

object GeoVaultInfoDialogDefaults {
    @Composable
    fun closeButtonColor() =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.PrimaryBlue
        } else {
            GeoVaultColorTokens.Gray300
        }
}

@Composable
fun GeoVaultInfoDialog(
    title: String,
    onDismissRequest: () -> Unit,
    closeButtonText: String = "Close",
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides Color.Black,
                    LocalTextStyle provides MaterialTheme.typography.body2,
                ) {
                    content()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text = closeButtonText,
                    color = GeoVaultInfoDialogDefaults.closeButtonColor()
                )
            }
        }
    )
}

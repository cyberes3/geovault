package com.geovault.common.ui.components

import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.geovault.common.ui.theme.GeoVaultColorTokens

object GeoVaultDialogDefaults {
    val NegativeButtonColor = GeoVaultColorTokens.Error

    @Composable
    fun cancelButtonColor() = GeoVaultColorTokens.PrimaryBlue
}

@Composable
fun GeoVaultConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmText: String = "Delete",
    cancelText: String = "Cancel"
) {
    val bodyColor = Color.Black
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.body2,
                color = bodyColor
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = GeoVaultDialogDefaults.NegativeButtonColor
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(
                    text = cancelText,
                    color = GeoVaultDialogDefaults.cancelButtonColor()
                )
            }
        }
    )
}

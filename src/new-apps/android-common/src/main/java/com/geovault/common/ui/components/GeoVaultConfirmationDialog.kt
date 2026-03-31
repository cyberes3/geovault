package com.geovault.common.ui.components

import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import com.geovault.common.ui.theme.GeoVaultColorTokens

object GeoVaultDialogDefaults {
    val NegativeButtonColor = GeoVaultColorTokens.Error

    @Composable
    fun cancelButtonColor() =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.PrimaryBlue
        } else {
            GeoVaultColorTokens.DarkOnSurface
        }
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
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = title) },
        text = { Text(text = message) },
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

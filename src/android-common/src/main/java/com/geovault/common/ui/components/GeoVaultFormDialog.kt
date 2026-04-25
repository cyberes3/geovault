package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Dialog primitive for custom form bodies with standard confirm/cancel buttons. Pairs well
 * with app-specific inputs that do not fit [GeoVaultInfoDialog] (which is scroll-only) nor
 * [GeoVaultConfirmationDialog] (which is text-only).
 *
 * - [title] follows the same typography as [GeoVaultInfoDialog]/[GeoVaultConfirmationDialog].
 * - [confirmEnabled] lets callers disable the primary action while the form is invalid.
 * - [body] renders inside a [Column] for straightforward stacking of form fields.
 */
@Composable
fun GeoVaultFormDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "Save",
    cancelText: String = "Cancel",
    confirmEnabled: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(content = body)
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    text = confirmText,
                    color = if (confirmEnabled) {
                        GeoVaultColorTokens.MainBlue
                    } else {
                        GeoVaultColorTokens.Gray400
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = cancelText,
                    color = GeoVaultColorTokens.MainBlue,
                )
            }
        },
    )
}

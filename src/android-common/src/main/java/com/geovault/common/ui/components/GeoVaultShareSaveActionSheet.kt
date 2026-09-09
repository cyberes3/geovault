package com.geovault.common.ui.components

import androidx.compose.runtime.Composable

/**
 * Second-level export menu: Share via the system sheet, or Save via SAF.
 */
@Composable
fun GeoVaultShareSaveActionSheet(
    title: String,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismissRequest: () -> Unit,
    shareLabel: String = "Share",
    saveLabel: String = "Save",
) {
    GeoVaultActionSheetDialog(
        title = title,
        options = listOf(
            GeoVaultActionSheetOption(
                label = shareLabel,
                onClick = {
                    onShare()
                    onDismissRequest()
                },
            ),
            GeoVaultActionSheetOption(
                label = saveLabel,
                onClick = {
                    onSave()
                    onDismissRequest()
                },
            ),
        ),
        onDismissRequest = onDismissRequest,
    )
}

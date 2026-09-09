package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class GeoVaultToggleListItem(
    val id: String,
    val label: String,
    val checked: Boolean,
)

@Composable
fun GeoVaultToggleListDialog(
    title: String,
    items: List<GeoVaultToggleListItem>,
    onToggle: (id: String, checked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    closeButtonText: String = "Done",
) {
    GeoVaultInfoDialog(
        modifier = modifier,
        title = title,
        onDismissRequest = onDismiss,
        closeButtonText = closeButtonText,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                GeoVaultToggle(
                    checked = item.checked,
                    onCheckedChange = { onToggle(item.id, it) },
                    label = item.label,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

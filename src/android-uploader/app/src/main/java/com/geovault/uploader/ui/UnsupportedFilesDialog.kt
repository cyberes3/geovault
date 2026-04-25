package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun UnsupportedFilesDialog(
    fileNames: List<String>,
    onDismissRequest: () -> Unit
) {
    val bodyColor =
        if (MaterialTheme.colors.isLight) GeoVaultColorTokens.TextPrimary else GeoVaultColorTokens.Dark.TextPrimary
    GeoVaultInfoDialog(
        title = "Unsupported files",
        onDismissRequest = onDismissRequest
    ) {
        Text(
            text = "Only KML, KMZ, and GPX files are supported. The following files were not added:",
            style = MaterialTheme.typography.body2,
            color = bodyColor
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fileNames.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.body2,
                    color = bodyColor
                )
            }
        }
    }
}

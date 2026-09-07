package com.geovault.common.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.files.GeoVaultFileTypeCatalog
import com.geovault.common.ui.components.GeoVaultInfoDialog

@Composable
fun GeoVaultRejectedIncomingFilesDialog(
    fileNames: List<String>,
    catalog: GeoVaultFileTypeCatalog,
    onDismissRequest: () -> Unit,
) {
    val labels = catalog.extensions.map { it.uppercase() }.sorted()
    val supported = when (labels.size) {
        0 -> "supported file types"
        1 -> labels[0]
        2 -> "${labels[0]} and ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + ", and ${labels.last()}"
    }
    val bodyColor = MaterialTheme.colors.onSurface
    GeoVaultInfoDialog(
        title = "Unsupported files",
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = "Only $supported files are supported. The following files were not added:",
            style = MaterialTheme.typography.body2,
            color = bodyColor,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fileNames.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.body2,
                    color = bodyColor,
                )
            }
        }
    }
}

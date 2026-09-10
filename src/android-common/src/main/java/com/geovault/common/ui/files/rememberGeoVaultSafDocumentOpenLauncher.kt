package com.geovault.common.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
fun rememberGeoVaultSafDocumentOpenLauncher(
    mimeTypes: Array<String>,
    onPicked: (Uri) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(mimeTypes) }
}

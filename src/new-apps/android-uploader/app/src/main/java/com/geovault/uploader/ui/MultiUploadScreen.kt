package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import com.geovault.uploader.presentation.QueueUploadState

@Composable
fun MultiUploadScreen(
    state: QueueUploadState,
    onOpenSettings: () -> Unit,
    onRename: (index: Int, String) -> Unit,
    onUploadClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = state.fileCountLabel,
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(onOpenSettings = onOpenSettings)
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(16.dp)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(state.items) { index, item ->
                    FileQueueRow(item = item, onRename = { onRename(index, it) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (state.progressMax > 1) {
                LinearProgressIndicator(
                    progress = if (state.progressMax == 0) 0f else state.progressCurrent.toFloat() / state.progressMax.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (state.statusMessage.isNotBlank()) {
                Text(state.statusMessage)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (!state.isUploading) {
                GeoVaultPrimaryButton(
                    text = "Upload All",
                    onClick = onUploadClick,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            GeoVaultSecondaryButton(
                text = if (state.isUploading) "Cancel" else "Close",
                onClick = onCancelClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FileQueueRow(item: FileQueueItem, onRename: (String) -> Unit) {
    val (basename, ext) = FilenamePolicy.splitFilename(item.filename)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (item.status) {
                    FileStatus.PENDING -> Icons.Filled.HourglassBottom
                    FileStatus.UPLOADING -> Icons.Filled.Upload
                    FileStatus.SUCCESS -> Icons.Filled.CheckCircle
                    FileStatus.ERROR -> Icons.Filled.Error
                },
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            GeoVaultInput(
                value = basename,
                onValueChange = onRename,
                label = "Filename",
                enabled = item.status == FileStatus.PENDING,
                modifier = Modifier.weight(1f),
            )
            if (ext.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(".$ext")
            }
        }
        Text("${item.sizeBytes} B", style = MaterialTheme.typography.caption)
        if (!item.errorMessage.isNullOrBlank()) {
            Text(item.errorMessage, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
        }
    }
}

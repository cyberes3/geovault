package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.uploader.R
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import com.geovault.uploader.presentation.QueueUploadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun MultiUploadScreen(
    state: QueueUploadState,
    invalidFilesDialogNames: List<String>?,
    onDismissInvalidFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (index: Int, String) -> Unit,
    onRemoveItem: (index: Int) -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                itemsIndexed(state.items) { index, item ->
                    FileQueueRow(
                        item = item,
                        onRename = { onRename(index, it) },
                        onRemove = { onRemoveItem(index) },
                        renameEnabled = !state.uploadCancelled && item.status == FileStatus.PENDING,
                        removeEnabled =
                            !state.isUploading &&
                                !state.uploadCancelled &&
                                item.status == FileStatus.PENDING
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Divider(
                    color = GeoVaultColorTokens.BorderLight,
                    thickness = 1.dp
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (state.progressMax > 1) {
                        LinearProgressIndicator(
                            progress = if (state.progressMax == 0) 0f else state.progressCurrent.toFloat() / state.progressMax.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (state.statusMessage.isNotBlank()) {
                        Text(
                            text = state.statusMessage,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    val hasPendingItems = state.items.any { it.status == FileStatus.PENDING }
                    if (!state.isUploading && !state.uploadCancelled && hasPendingItems) {
                        GeoVaultPrimaryButton(
                            text = "Upload All",
                            onClick = onUploadClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (state.isUploading) {
                        GeoVaultSecondaryButton(
                            text = "Cancel",
                            onClick = onCancelClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
    invalidFilesDialogNames?.let { names ->
        UnsupportedFilesDialog(
            fileNames = names,
            onDismissRequest = onDismissInvalidFiles
        )
    }
}

@Composable
private fun FileQueueRow(
    item: FileQueueItem,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    renameEnabled: Boolean,
    removeEnabled: Boolean
) {
    val (basename, ext) = FilenamePolicy.splitFilename(item.filename)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
        backgroundColor = Color.White,
        border = BorderStroke(1.dp, GeoVaultColorTokens.BorderLight),
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusIcon = when (item.status) {
                    FileStatus.PENDING -> null
                    FileStatus.UPLOADING -> Icons.Filled.Upload
                    FileStatus.SUCCESS -> Icons.Filled.CheckCircle
                    FileStatus.ERROR -> Icons.Filled.Error
                }
                if (statusIcon == null) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_file),
                        contentDescription = null,
                        tint = GeoVaultColorTokens.MainBlue,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.MainBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                GeoVaultInput(
                    value = basename,
                    onValueChange = onRename,
                    label = "Filename",
                    enabled = renameEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { bringIntoViewRequester.bringIntoView() }
                            }
                        },
                )
                if (ext.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(".$ext")
                }
                if (removeEnabled) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove file",
                            modifier = Modifier.size(20.dp),
                            tint = if (MaterialTheme.colors.isLight) {
                                GeoVaultColorTokens.Error
                            } else {
                                GeoVaultColorTokens.Dark.Error
                            }
                        )
                    }
                }
            }
            Text(
                text = buildMetadataLine(item.sizeBytes, item.modifiedAtMs),
                style = MaterialTheme.typography.caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (!item.errorMessage.isNullOrBlank()) {
                Text(item.errorMessage, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
            }
        }
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1024L) return "$sizeBytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val base = 1024.0
    val exp = (ln(sizeBytes.toDouble()) / ln(base)).toInt().coerceAtMost(units.size)
    val value = sizeBytes / base.pow(exp.toDouble())
    val unit = units[exp - 1]
    val rounded = if (value >= 10) {
        String.format(Locale.getDefault(), "%.0f", value)
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }
    return "$rounded $unit"
}

private fun buildMetadataLine(sizeBytes: Long, modifiedAtMs: Long?): String {
    val sizeText = formatFileSize(sizeBytes)
    val modifiedText = modifiedAtMs?.let { "Modified ${formatModifiedDate(it)}" } ?: "Modified unknown"
    return "$sizeText • $modifiedText"
}

private fun formatModifiedDate(timestampMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    return Instant.ofEpochMilli(timestampMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(formatter)
}

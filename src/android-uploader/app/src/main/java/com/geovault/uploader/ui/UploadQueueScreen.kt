package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geovault.common.files.GeoVaultFilename
import com.geovault.uploader.files.UploaderFileTypes
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultEmptyState
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultOutlinedStrokeCard
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.files.GeoVaultRejectedIncomingFilesDialog
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.common.ui.time.GeoVaultDateTimeFormat
import com.geovault.common.util.GeoVaultFileSizeFormat
import com.geovault.uploader.R
import com.geovault.uploader.domain.UploadItemId
import com.geovault.uploader.domain.UploadItemState
import com.geovault.uploader.presentation.UploadItemUi
import com.geovault.uploader.presentation.UploadQueueUiState
import kotlinx.coroutines.launch

@Composable
fun UploadQueueScreen(
    state: UploadQueueUiState,
    auth: GeoVaultAuthShellState,
    onRename: (UploadItemId, String) -> Unit,
    onRemoveItem: (UploadItemId) -> Unit,
    onUploadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onCloseClick: () -> Unit,
    onDismissInvalidFiles: () -> Unit,
) {
    GeoVaultTabShell(
        title = pluralStringResource(R.plurals.queue_file_count, state.items.size, state.items.size),
        auth = auth,
        modifier = Modifier.imePadding(),
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        applyNavigationBarsPadding = true,
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 16.dp,
        authenticatedMainContent = {
            if (state.items.isEmpty()) {
                GeoVaultEmptyState(
                    title = stringResource(R.string.no_files_to_upload),
                    message = stringResource(R.string.no_files_to_upload_message),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    items(state.items, key = { it.id.value }) { item ->
                        FileQueueRow(
                            item = item,
                            onRename = { onRename(item.id, it) },
                            onRemove = { onRemoveItem(item.id) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        authenticatedFooter = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Divider(
                    color = geoVaultHairlineDividerColor(),
                    thickness = 1.dp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (state.progressMax > 1) {
                        LinearProgressIndicator(
                            progress = if (state.progressMax == 0) {
                                0f
                            } else {
                                state.progressCurrent.toFloat() / state.progressMax.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (state.statusMessage.isNotBlank()) {
                        Text(
                            text = state.statusMessage,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (state.showUploadAll) {
                        GeoVaultPrimaryButton(
                            text = stringResource(R.string.upload_all),
                            onClick = onUploadClick,
                            tooltip = stringResource(R.string.tooltip_upload_all),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (state.showCancel) {
                        GeoVaultSecondaryButton(
                            text = stringResource(R.string.cancel_button),
                            onClick = onCancelClick,
                            tooltip = stringResource(R.string.tooltip_cancel_upload),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        GeoVaultSecondaryButton(
                            text = stringResource(R.string.close_button),
                            onClick = onCloseClick,
                            tooltip = stringResource(R.string.tooltip_cancel),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    )
    if (state.showRejectedDialog && state.rejectedFileNames.isNotEmpty()) {
        GeoVaultRejectedIncomingFilesDialog(
            fileNames = state.rejectedFileNames,
            catalog = UploaderFileTypes.catalog,
            onDismissRequest = onDismissInvalidFiles,
        )
    }
}

@Composable
private fun FileQueueRow(
    item: UploadItemUi,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val (basename, ext) = GeoVaultFilename.splitBaseAndExtension(item.displayName)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    GeoVaultOutlinedStrokeCard(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueueStatusIcon(state = item.state)
                Spacer(modifier = Modifier.width(8.dp))
                GeoVaultInput(
                    value = basename,
                    onValueChange = onRename,
                    label = stringResource(R.string.filename_label),
                    enabled = item.canRename,
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
                if (item.canRemove) {
                    GeoVaultIconButton(
                        onClick = onRemove,
                        enabled = true,
                        tooltip = stringResource(R.string.remove_file),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.remove_file),
                            modifier = Modifier.size(20.dp),
                            tint = if (MaterialTheme.colors.isLight) {
                                GeoVaultColorTokens.Error
                            } else {
                                GeoVaultColorTokens.Dark.Error
                            },
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }
            Text(
                text = metadataLine(item.sizeBytes, item.modifiedAtMs),
                style = MaterialTheme.typography.caption,
                color = geoVaultContentSecondaryColor(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!item.errorMessage.isNullOrBlank()) {
                Text(
                    text = item.errorMessage,
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}

@Composable
private fun QueueStatusIcon(state: UploadItemState) {
    val icon: ImageVector?
    val tint: Color
    val description: String?
    when (state) {
        UploadItemState.Queued -> {
            icon = null
            tint = GeoVaultColorTokens.MainBlue
            description = null
        }
        UploadItemState.Uploading -> {
            icon = Icons.Filled.Upload
            tint = GeoVaultColorTokens.MainBlue
            description = stringResource(R.string.status_uploading)
        }
        UploadItemState.Succeeded -> {
            icon = Icons.Filled.CheckCircle
            tint = GeoVaultColorTokens.Success
            description = stringResource(R.string.status_upload_succeeded)
        }
        is UploadItemState.Failed -> {
            icon = Icons.Filled.Error
            tint = if (MaterialTheme.colors.isLight) {
                GeoVaultColorTokens.Error
            } else {
                GeoVaultColorTokens.Dark.Error
            }
            description = stringResource(R.string.status_upload_failed)
        }
    }
    if (icon == null) {
        Icon(
            painter = painterResource(id = R.drawable.ic_file),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun metadataLine(sizeBytes: Long, modifiedAtMs: Long?): String {
    val sizeText = GeoVaultFileSizeFormat.humanBytesOrUnknown(sizeBytes)
    val modifiedText = modifiedAtMs?.let {
        stringResource(R.string.file_modified, GeoVaultDateTimeFormat.formatLocalDate(it))
    } ?: stringResource(R.string.file_modified_unknown)
    return "$sizeText • $modifiedText"
}

package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultFormSection
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultStatusPane
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.ImportantMessage
import com.geovault.common.ui.components.ImportantMessageHost
import com.geovault.common.ui.theme.GeoVaultLayoutTokens
import com.geovault.uploader.presentation.MainScreenState

@Composable
fun MainScreen(
    state: MainScreenState,
    invalidFilesDialogNames: List<String>?,
    onOpenSettings: () -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onChooseFileClick: () -> Unit,
    onFilenameChanged: (String) -> Unit,
    onUploadClick: () -> Unit,
    onCloseClick: () -> Unit,
    onDismissImportant: () -> Unit,
    onDismissInvalidFiles: () -> Unit,
    onDismissUpdatePrompt: () -> Unit,
    onOpenUpdateUrl: () -> Unit
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "GeoVault Uploader",
                hideIconActions = !state.isAuthenticated,
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(onOpenSettings = onOpenSettings)
                }
            )
        }
    ) { padding ->
        GeoVaultAuthGate(
            isAuthenticated = state.isAuthenticated,
            serverUrl = state.serverUrl,
            onServerUrlChanged = onAuthServerUrlChanged,
            onConnect = onAuthConnect,
            isConnecting = state.isConnecting,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(GeoVaultLayoutTokens.ScreenPadding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.isValidationMode) {
                    GeoVaultStatusPane(
                        model = MainScreenStatusMapper.toValidationStatusModel(state),
                        onPrimaryActionClick = onChooseFileClick,
                        onSecondaryActionClick = onOpenSettings
                    )
                } else {
                    UploadContent(
                        filename = state.editedFilename,
                        suffixPreview = state.suffixPreview,
                        isUploading = state.isUploading,
                        statusMessage = state.statusMessage,
                        onFilenameChanged = onFilenameChanged,
                        onUploadClick = onUploadClick,
                        onCloseClick = onCloseClick
                    )
                }

                val important = state.importantMessage?.let { ImportantMessage(it) }
                ImportantMessageHost(
                    message = important,
                    onDismiss = onDismissImportant,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                val updateMessage = state.updatePromptMessage
                if (!updateMessage.isNullOrBlank()) {
                    ImportantMessageHost(
                        message = ImportantMessage(
                            text = updateMessage,
                            actionLabel = "Open",
                            onAction = onOpenUpdateUrl
                        ),
                        onDismiss = onDismissUpdatePrompt,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                    )
                }
                invalidFilesDialogNames?.let { names ->
                    UnsupportedFilesDialog(
                        fileNames = names,
                        onDismissRequest = onDismissInvalidFiles
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadContent(
    filename: String,
    suffixPreview: String,
    isUploading: Boolean,
    statusMessage: String,
    onFilenameChanged: (String) -> Unit,
    onUploadClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val isUploadSuccess = statusMessage.startsWith("Upload successful", ignoreCase = true)
    GeoVaultFormSection(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalGap = GeoVaultLayoutTokens.SectionGap
    ) {
        Text(
            text = "Filename",
            style = MaterialTheme.typography.body1.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
        GeoVaultInput(
            value = filename,
            onValueChange = onFilenameChanged,
            label = "Filename",
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = suffixPreview,
            style = MaterialTheme.typography.body2
        )
        GeoVaultPrimaryButton(
            text = "Upload",
            enabled = !isUploading && !isUploadSuccess,
            onClick = onUploadClick,
            modifier = Modifier.fillMaxWidth()
        )
        GeoVaultSecondaryButton(
            text = "Cancel",
            enabled = !isUploading,
            onClick = onCloseClick,
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GeoVaultLayoutTokens.TightGap)
            ) {
                if (isUploading) {
                    GeoVaultLoadingSpinner()
                }
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage)
                }
            }
        }
    }
}

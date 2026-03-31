package com.geovault.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.ImportantMessage
import com.geovault.common.ui.components.ImportantMessageHost
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.uploader.presentation.MainScreenState

@Composable
fun MainScreen(
    state: MainScreenState,
    onOpenSettings: () -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onChooseFileClick: () -> Unit,
    onFilenameChanged: (String) -> Unit,
    onUploadClick: () -> Unit,
    onCloseClick: () -> Unit,
    onDismissImportant: () -> Unit,
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
                .padding(20.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                if (state.isValidationMode) {
                    ValidationContent(
                        title = state.validationTitle,
                        message = state.validationMessage,
                        isLoading = state.isValidationLoading,
                        onChooseFileClick = onChooseFileClick
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
            }
        }
    }
}

@Composable
private fun ValidationContent(
    title: String,
    message: String,
    isLoading: Boolean,
    onChooseFileClick: () -> Unit
) {
    val isSuccess = message.startsWith("✓")
    val cleanMessage = if (isSuccess) message.removePrefix("✓").trimStart() else message

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLoading) {
            GeoVaultLoadingSpinner()
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!isSuccess) {
            Text(title)
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (isSuccess && !isLoading) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Connected",
                tint = GeoVaultColorTokens.PrimaryBlue
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = cleanMessage,
            textAlign = TextAlign.Center
        )
        if (isSuccess && !isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            GeoVaultPrimaryButton(
                text = "Choose File",
                onClick = onChooseFileClick
            )
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
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Filename", style = MaterialTheme.typography.subtitle1)
        Spacer(modifier = Modifier.height(8.dp))
        GeoVaultInput(
            value = filename,
            onValueChange = onFilenameChanged,
            label = "Filename",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(suffixPreview, style = MaterialTheme.typography.body2)
        Spacer(modifier = Modifier.height(20.dp))
        GeoVaultPrimaryButton(
            text = "Upload",
            enabled = !isUploading,
            onClick = onUploadClick,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        GeoVaultSecondaryButton(
            text = "Cancel",
            enabled = !isUploading,
            onClick = onCloseClick,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (isUploading) GeoVaultLoadingSpinner()
        if (statusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(statusMessage)
        }
    }
}

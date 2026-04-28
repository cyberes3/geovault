package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.navigation.MultiUploadNavigation
import com.geovault.uploader.presentation.QueueUploadViewModel
import com.geovault.uploader.ui.MultiUploadScreen

class MultiUploadActivity : ComponentActivity() {
    private val viewModel: QueueUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize(intent)
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                var invalidFilesDialogNames by rememberSaveable {
                    mutableStateOf(
                        MultiUploadNavigation.readRejectedFileNames(intent)
                            .takeIf { it.isNotEmpty() }
                    )
                }
                BackHandler {
                    if (state.isUploading) {
                        viewModel.cancelUpload()
                    } else {
                        finish()
                    }
                }
                MultiUploadScreen(
                    state = state,
                    invalidFilesDialogNames = invalidFilesDialogNames,
                    onDismissInvalidFiles = { invalidFilesDialogNames = null },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onRename = viewModel::rename,
                    onRemoveItem = viewModel::removeItemAt,
                    onUploadClick = viewModel::startUpload,
                    onCancelClick = {
                        if (state.isUploading) {
                            viewModel.cancelUpload()
                        } else {
                            finish()
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initialize(intent)
    }
}

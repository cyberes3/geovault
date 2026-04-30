package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.components.GeoVaultPrewarmedOverlayHost
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.navigation.MultiUploadNavigation
import com.geovault.uploader.presentation.QueueUploadViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.ui.MultiUploadScreen
import com.geovault.uploader.ui.SettingsScreen

class MultiUploadActivity : ComponentActivity() {
    private val viewModel: QueueUploadViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize(intent)
        settingsViewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var invalidFilesDialogNames by rememberSaveable {
                    mutableStateOf(
                        MultiUploadNavigation.readRejectedFileNames(intent)
                            .takeIf { it.isNotEmpty() }
                    )
                }
                BackHandler(enabled = !isSettingsOpen) {
                    if (state.isUploading) {
                        viewModel.cancelUpload()
                    } else {
                        finish()
                    }
                }
                BackHandler(enabled = isSettingsOpen) {
                    isSettingsOpen = false
                }
                LaunchedEffect(settingsState.oauthUrl) {
                    val oauthUrl = settingsState.oauthUrl
                    if (!oauthUrl.isNullOrBlank()) {
                        GeovaultAuthManager.launchOAuthInBrowser(this@MultiUploadActivity, oauthUrl)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    MultiUploadScreen(
                        state = state,
                        invalidFilesDialogNames = invalidFilesDialogNames,
                        onDismissInvalidFiles = { invalidFilesDialogNames = null },
                        isSettingsOverlayVisible = isSettingsOpen,
                        onOpenSettings = { isSettingsOpen = true },
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
                    GeoVaultPrewarmedOverlayHost(visible = isSettingsOpen) {
                        SettingsScreen(
                            state = settingsState,
                            onServerUrlChanged = settingsViewModel::onServerUrlChanged,
                            onSuffixChanged = settingsViewModel::onSuffixChanged,
                            onConnect = settingsViewModel::connect,
                            onDisconnect = { settingsViewModel.disconnect(MainActivity::class.java) },
                            onClose = { isSettingsOpen = false },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsViewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        settingsViewModel.onOauthUrlConsumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initialize(intent)
    }
}

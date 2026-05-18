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
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.navigation.MultiUploadNavigation
import com.geovault.uploader.presentation.UploaderAccountViewModel
import com.geovault.uploader.presentation.QueueUploadViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.ui.MultiUploadScreen
import com.geovault.uploader.ui.SettingsScreen

class MultiUploadActivity : ComponentActivity() {
    private val viewModel: QueueUploadViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: UploaderAccountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        accountViewModel.initialize()
        viewModel.initialize(intent)
        settingsViewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
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
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    MultiUploadScreen(
                        state = state,
                        invalidFilesDialogNames = invalidFilesDialogNames,
                        onDismissInvalidFiles = { invalidFilesDialogNames = null },
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
                    GeoVaultShellSettingsOverlayHost(
                        visible = isSettingsOpen,
                        onDismissRequest = { isSettingsOpen = false },
                    ) {
                        SettingsScreen(
                            state = settingsState,
                            accountState = accountState,
                            onServerUrlChanged = accountViewModel::onServerUrlChanged,
                            onSuffixChanged = settingsViewModel::onSuffixChanged,
                            onConnect = accountViewModel::connect,
                            onDisconnect = { accountViewModel.disconnect(MainActivity::class.java) },
                            onClose = { isSettingsOpen = false },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accountViewModel.onHostResumed()
        settingsViewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        accountViewModel.onOauthUrlConsumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initialize(intent)
    }
}

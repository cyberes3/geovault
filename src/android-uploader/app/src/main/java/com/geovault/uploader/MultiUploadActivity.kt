package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.intent.GeoVaultShareSession
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.rememberGeoVaultAuthShellState
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.presentation.UploadViewModel
import com.geovault.uploader.ui.MultiUploadScreen
import com.geovault.uploader.ui.SettingsScreen

class MultiUploadActivity : ComponentActivity() {
    private val viewModel: UploadViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(UploaderAppServices.from(application).initialAuthController())
    }
    private val shareSession = GeoVaultShareSession()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!shareSession.beginOrRelocate(this, savedInstanceState)) {
            return
        }
        GeoVaultAuthHost.installSplash(
            this,
            (application as UploaderApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        GeoVaultAuthHost.onCreate(this, accountViewModel)
        viewModel.initialize(intent)
        shareSession.consumeIncoming(intent)
        shareSession.onStandaloneUiReady()
        settingsViewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                LaunchedEffect(accountState.isLoggedIn) {
                    viewModel.onAccountStateChanged(accountState)
                }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var rejectedDialogDismissed by rememberSaveable { mutableStateOf(false) }
                var lastRejectedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
                LaunchedEffect(state.rejectedFileNames) {
                    if (state.rejectedFileNames != lastRejectedNames) {
                        lastRejectedNames = state.rejectedFileNames
                        rejectedDialogDismissed = false
                    }
                }
                val invalidFilesDialogNames = state.rejectedFileNames
                    .takeIf { it.isNotEmpty() && !rejectedDialogDismissed }
                BackHandler(enabled = !isSettingsOpen) {
                    if (state.isUploading) {
                        viewModel.cancelUpload()
                    } else {
                        shareSession.finish(this@MultiUploadActivity)
                    }
                }
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
                val auth = rememberGeoVaultAuthShellState(
                    accountState = accountState,
                    onServerUrlChanged = accountViewModel::onServerUrlChanged,
                    onConnect = accountViewModel::connect,
                    onOpenSettings = openSettingsOverlay,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    MultiUploadScreen(
                        state = state,
                        auth = auth,
                        invalidFilesDialogNames = invalidFilesDialogNames,
                        onDismissInvalidFiles = { rejectedDialogDismissed = true },
                        onRename = viewModel::rename,
                        onRemoveItem = viewModel::removeItemAt,
                        onUploadClick = viewModel::startUpload,
                        onCancelClick = {
                            if (state.isUploading) {
                                viewModel.cancelUpload()
                            } else {
                                shareSession.finish(this@MultiUploadActivity)
                            }
                        }
                    )
                    GeoVaultAppSnackbarLayer(
                        snackbar = null,
                        onDismissSnackbar = {},
                        update = state.updateAvailable,
                        onDismissUpdate = viewModel::clearUpdateAvailable,
                    )
                    GeoVaultShellSettingsOverlayHost(
                        visible = isSettingsOpen,
                        onDismissRequest = { isSettingsOpen = false },
                    ) {
                        GeoVaultShellOverlayScaffold(
                            title = stringResource(R.string.settings_title),
                            onClose = { isSettingsOpen = false },
                        ) { padding ->
                            SettingsScreen(
                                state = settingsState,
                                accountState = accountState,
                                onServerUrlChanged = accountViewModel::onServerUrlChanged,
                                onSuffixChanged = settingsViewModel::onSuffixChanged,
                                onConnect = accountViewModel::connect,
                                onDisconnect = { accountViewModel.disconnect(MainActivity::class.java) },
                                contentPadding = padding,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GeoVaultAuthHost.onResume(accountViewModel)
        settingsViewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        GeoVaultAuthHost.onStop(accountViewModel)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GeoVaultAuthHost.onNewIntent(intent, accountViewModel)
        viewModel.initialize(intent)
        shareSession.consumeIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shareSession.persist(outState)
    }
}

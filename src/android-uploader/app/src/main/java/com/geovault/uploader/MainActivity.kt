package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.uploader.files.UploaderFileTypes
import com.geovault.common.intent.GeoVaultShareCloseAction
import com.geovault.common.intent.GeoVaultShareClosePolicy
import com.geovault.common.intent.GeoVaultShareSession
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.rememberGeoVaultAuthShellState
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.presentation.MainScreenViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.presentation.UploadViewModel
import com.geovault.uploader.presentation.UploaderDestination
import com.geovault.uploader.ui.MainScreen
import com.geovault.uploader.ui.SettingsScreen
import com.geovault.uploader.ui.UploadQueueScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels()
    private val uploadViewModel: UploadViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(UploaderAppServices.from(application).initialAuthController())
    }
    private val shareSession = GeoVaultShareSession()
    private lateinit var chooseFilesLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        GeoVaultAuthHost.installSplash(
            this,
            (application as UploaderApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        if (!shareSession.beginOrRelocate(this, savedInstanceState)) {
            return
        }
        chooseFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val picked = uris.orEmpty()
            if (picked.isEmpty()) return@registerForActivityResult
            if (uploadViewModel.ingestPickerUris(picked)) {
                viewModel.showQueue()
            }
        }
        GeoVaultAuthHost.onCreate(this, accountViewModel)
        uploadViewModel.bindIncomingCloseReturnsToSender(
            savedInstanceState?.getBoolean(STATE_INCOMING_CLOSE_RETURNS_TO_SENDER) ?: false,
        )
        if (savedInstanceState?.getBoolean(STATE_SHOW_QUEUE) == true) {
            viewModel.showQueue()
        }
        consumeIncoming(intent, deliveredToRunningInstance = false)
        shareSession.onStandaloneUiReady()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val uploadState by uploadViewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                LaunchedEffect(accountState.isLoggedIn) {
                    viewModel.onAccountStateChanged(accountState)
                }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
                val auth = rememberGeoVaultAuthShellState(
                    accountState = accountState,
                    onServerUrlChanged = accountViewModel::onServerUrlChanged,
                    onConnect = accountViewModel::connect,
                    onOpenSettings = openSettingsOverlay,
                )
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                val onQueueClose: () -> Unit = {
                    when (
                        GeoVaultShareClosePolicy.decide(
                            isIncomingShareFlow = uploadState.isIncomingShareFlow,
                            keepHostOpen = uploadState.incomingCloseReturnsToSender,
                        )
                    ) {
                        GeoVaultShareCloseAction.ExitHost -> shareSession.finish(this@MainActivity)
                        GeoVaultShareCloseAction.ReturnToSender -> shareSession.returnToSender(this@MainActivity)
                        GeoVaultShareCloseAction.DismissLocalUi -> viewModel.showHome()
                    }
                }
                BackHandler(enabled = !isSettingsOpen) {
                    if (state.destination is UploaderDestination.Queue) {
                        if (uploadState.showCancel) {
                            uploadViewModel.cancelUpload()
                        } else {
                            onQueueClose()
                        }
                    } else {
                        finish()
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    when (state.destination) {
                        UploaderDestination.Home -> MainScreen(
                            state = state,
                            auth = auth,
                            onChooseFileClick = {
                                chooseFilesLauncher.launch(UploaderFileTypes.pickerMimeTypes)
                            },
                            onOpenSettings = openSettingsOverlay,
                        )
                        UploaderDestination.Queue -> UploadQueueScreen(
                            state = uploadState,
                            auth = auth,
                            onRename = uploadViewModel::rename,
                            onRemoveItem = uploadViewModel::removeItem,
                            onUploadClick = uploadViewModel::startUpload,
                            onCancelClick = uploadViewModel::cancelUpload,
                            onCloseClick = onQueueClose,
                            onDismissInvalidFiles = uploadViewModel::dismissRejectedFilesDialog,
                        )
                    }
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
                    GeoVaultAppSnackbarLayer(
                        snackbar = null,
                        onDismissSnackbar = {},
                        update = state.updateAvailable,
                        onDismissUpdate = viewModel::clearUpdateAvailable,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GeoVaultAuthHost.onResume(accountViewModel)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GeoVaultAuthHost.onNewIntent(intent, accountViewModel)
        consumeIncoming(intent, deliveredToRunningInstance = true)
    }

    override fun onStop() {
        super.onStop()
        GeoVaultAuthHost.onStop(accountViewModel)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shareSession.persist(outState)
        outState.putBoolean(STATE_SHOW_QUEUE, viewModel.state.value.destination is UploaderDestination.Queue)
        outState.putBoolean(
            STATE_INCOMING_CLOSE_RETURNS_TO_SENDER,
            uploadViewModel.incomingCloseReturnsToSender(),
        )
    }

    private fun consumeIncoming(intent: Intent?, deliveredToRunningInstance: Boolean) {
        if (uploadViewModel.ingestIntent(intent, deliveredToRunningInstance)) {
            viewModel.showQueue()
        }
    }

    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
        private const val STATE_SHOW_QUEUE = "uploader_show_queue"
        private const val STATE_INCOMING_CLOSE_RETURNS_TO_SENDER = "uploader_incoming_close_returns_to_sender"
    }
}

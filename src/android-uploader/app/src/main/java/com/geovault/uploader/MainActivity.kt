package com.geovault.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.files.GeoVaultUploadFileTypes
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.navigation.UploadNavigation
import com.geovault.uploader.presentation.HomeViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.ui.MainScreen
import com.geovault.uploader.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(UploaderAppServices.from(application).initialAuthController())
    }
    private val services: UploaderAppServices by lazy { UploaderAppServices.from(application) }
    private lateinit var chooseFilesLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        com.geovault.common.ui.splash.GeoVaultSplashScreen.install(
            this,
            (application as UploaderApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        chooseFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            routeUrisToUpload(uris.orEmpty(), finishAfterStart = false)
        }
        GeoVaultSystemBars.applyAppChrome(activity = this)
        accountViewModel.initialize()
        viewModel.initialize(intent)
        settingsViewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                LaunchedEffect(accountState.isLoggedIn, accountState.serverUrl, accountState.isConnecting) {
                    viewModel.onAccountStateChanged(accountState)
                }
                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let { error ->
                        accountViewModel.showExternalError(error)
                        intent.removeExtra(EXTRA_OAUTH_ERROR)
                    }
                }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                val openSettingsOverlay: () -> Unit = { isSettingsOpen = true }
                val mergedState = state.copy(
                    isAuthenticated = accountState.isLoggedIn,
                    serverUrl = accountState.serverUrl,
                    isConnecting = accountState.isConnecting,
                )
                val auth = remember(
                    mergedState.isAuthenticated,
                    mergedState.serverUrl,
                    mergedState.isConnecting,
                ) {
                    GeoVaultAuthShellState(
                        isAuthenticated = mergedState.isAuthenticated,
                        serverUrl = mergedState.serverUrl,
                        onServerUrlChanged = accountViewModel::onServerUrlChanged,
                        onConnect = accountViewModel::connect,
                        onOpenSettings = openSettingsOverlay,
                        isConnecting = mergedState.isConnecting,
                    )
                }
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        state = mergedState,
                        auth = auth,
                        onChooseFileClick = {
                            chooseFilesLauncher.launch(GeoVaultUploadFileTypes.supportedMimeTypes)
                        },
                        onOpenSettings = openSettingsOverlay,
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
                    GeoVaultAppSnackbarLayer(
                        snackbar = mergedState.importantSnackbar,
                        onDismissSnackbar = viewModel::clearImportantMessage,
                        update = mergedState.updateAvailable,
                        onDismissUpdate = viewModel::clearUpdateAvailable,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accountViewModel.onHostResumed()
        viewModel.onHostResumed()
        settingsViewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        accountViewModel.onOauthUrlConsumed()
    }

    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
    }

    private fun routeUrisToUpload(uris: List<Uri>, finishAfterStart: Boolean) {
        val result = services.fileIngest.ingest(uris, GeoVaultFileRef.Source.Picker)
        if (result.accepted.isEmpty() && result.rejectedFileNames.isEmpty()) return
        startActivity(
            UploadNavigation.createIntent(
                context = this,
                supportedUris = result.accepted.map { it.uri },
                rejectedFileNames = result.rejectedFileNames,
            )
        )
        if (finishAfterStart) finish()
    }
}

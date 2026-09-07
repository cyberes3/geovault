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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.files.GeoVaultUploadFileTypes
import com.geovault.common.ui.GeoVaultAppSnackbarLayer
import com.geovault.common.ui.GeoVaultShellOverlayScaffold
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.rememberGeoVaultAuthShellState
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
    private lateinit var chooseFilesLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        GeoVaultAuthHost.installSplash(
            this,
            (application as UploaderApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        chooseFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            routeUrisToUpload(uris.orEmpty(), finishAfterStart = false)
        }
        GeoVaultAuthHost.onCreate(this, accountViewModel)
        viewModel.initialize(intent)
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
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        state = state,
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
                        snackbar = state.importantSnackbar,
                        onDismissSnackbar = viewModel::clearImportantMessage,
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
        viewModel.onHostResumed()
        settingsViewModel.onHostResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GeoVaultAuthHost.onNewIntent(intent, accountViewModel)
    }

    override fun onStop() {
        super.onStop()
        GeoVaultAuthHost.onStop(accountViewModel)
    }

    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
    }

    private fun routeUrisToUpload(uris: List<Uri>, finishAfterStart: Boolean) {
        if (uris.isEmpty()) return
        startActivity(
            UploadNavigation.createIntent(
                context = this,
                supportedUris = uris,
                source = GeoVaultFileRef.Source.Picker,
            )
        )
        if (finishAfterStart) finish()
    }
}

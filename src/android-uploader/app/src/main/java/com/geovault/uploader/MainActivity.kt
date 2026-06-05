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
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.files.GeoVaultUploadFileTypes
import com.geovault.common.ui.auth.GeoVaultOAuthBrowserEffect
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.domain.PickerRouteDecision
import com.geovault.uploader.navigation.UploadNavigation
import com.geovault.uploader.presentation.HomeViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.presentation.UploaderAccountViewModel
import com.geovault.uploader.ui.MainScreen
import com.geovault.uploader.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: UploaderAccountViewModel by viewModels()
    private val services: UploaderAppServices by lazy { UploaderAppServices.from(application) }
    private lateinit var chooseFilesLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
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
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        state = state.copy(
                            isAuthenticated = accountState.isLoggedIn,
                            serverUrl = accountState.serverUrl,
                            isConnecting = accountState.isConnecting,
                        ),
                        onOpenSettings = { isSettingsOpen = true },
                        onAuthServerUrlChanged = accountViewModel::onServerUrlChanged,
                        onAuthConnect = accountViewModel::connect,
                        onChooseFileClick = {
                            chooseFilesLauncher.launch(GeoVaultUploadFileTypes.supportedMimeTypes)
                        },
                        onDismissImportant = viewModel::clearImportantMessage,
                        onDismissUpdateAvailable = viewModel::clearUpdateAvailable,
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
        when (val decision = services.pickerSelectionRouter.decide(uris, applyExtensionFilter = true)) {
            PickerRouteDecision.NoSelection -> Unit
            is PickerRouteDecision.RejectedOnly -> {
                startActivity(
                    UploadNavigation.createIntent(
                        context = this,
                        supportedUris = emptyList(),
                        rejectedFileNames = decision.rejectedFileNames,
                    )
                )
                if (finishAfterStart) finish()
            }
            is PickerRouteDecision.SupportedSelection -> {
                startActivity(
                    UploadNavigation.createIntent(
                        context = this,
                        supportedUris = decision.uris,
                        rejectedFileNames = decision.rejectedFileNames,
                    )
                )
                if (finishAfterStart) finish()
            }
        }
    }
}

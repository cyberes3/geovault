package com.geovault.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.ui.components.GeoVaultShellSettingsOverlayHost
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.data.FileMetadataRepository
import com.geovault.uploader.domain.PickerRouteDecision
import com.geovault.uploader.domain.PickerSelectionRouter
import com.geovault.uploader.navigation.MultiUploadNavigation
import com.geovault.uploader.presentation.MainScreenViewModel
import com.geovault.uploader.presentation.SettingsViewModel
import com.geovault.uploader.presentation.UploaderAccountViewModel
import com.geovault.uploader.ui.MainScreen
import com.geovault.uploader.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val accountViewModel: UploaderAccountViewModel by viewModels()
    private val fileMetadataRepository: FileMetadataRepository by lazy {
        FileMetadataRepository(contentResolver)
    }
    private val pickerSelectionRouter: PickerSelectionRouter by lazy {
        PickerSelectionRouter(fileMetadataRepository)
    }
    private val invalidFilesDialogNamesState = mutableStateOf<List<String>?>(null)
    private lateinit var chooseFilesLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chooseFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            routeUrisToUploadTarget(uris.orEmpty(), applyExtensionFilter = true)
        }
        GeoVaultSystemBars.applyAppChrome(activity = this)
        val incomingRoute = routeIncomingIntentToUploadTarget(intent)
        accountViewModel.initialize()
        viewModel.initialize(intent, handleFileIntent = !incomingRoute.handled)
        settingsViewModel.initialize()
        if (incomingRoute.finishedActivity) return
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val accountState by accountViewModel.state.collectAsState()
                val accountMainState = state.copy(
                    isAuthenticated = accountState.isLoggedIn,
                    serverUrl = accountState.serverUrl,
                    isConnecting = accountState.isConnecting,
                    oauthUrl = null,
                )
                LaunchedEffect(accountState.isLoggedIn, accountState.serverUrl, accountState.isConnecting) {
                    viewModel.onAccountStateChanged(accountState)
                }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                GeoVaultOAuthBrowserEffect(
                    oauthUrl = accountState.oauthUrl,
                    onConsumed = accountViewModel::onOauthUrlConsumed,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        state = accountMainState,
                        invalidFilesDialogNames = invalidFilesDialogNamesState.value,
                        onOpenSettings = { isSettingsOpen = true },
                        onAuthServerUrlChanged = accountViewModel::onServerUrlChanged,
                        onAuthConnect = accountViewModel::connect,
                        onChooseFileClick = {
                            chooseFilesLauncher.launch(arrayOf("*/*"))
                        },
                        onFilenameChanged = viewModel::onFilenameChanged,
                        onUploadClick = {
                            viewModel.uploadCurrentFile(onSuccessClose = { finish() })
                        },
                        onCloseClick = { finish() },
                        onDismissImportant = viewModel::clearImportantMessage,
                        onDismissInvalidFiles = { invalidFilesDialogNamesState.value = null },
                        onDismissUpdateAvailable = viewModel::clearUpdateAvailable
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingRoute = routeIncomingIntentToUploadTarget(intent)
        viewModel.initialize(intent, handleFileIntent = !incomingRoute.handled)
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

    private fun routeIncomingIntentToUploadTarget(intent: Intent?): IncomingRouteResult {
        val incomingUris = when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            Intent.ACTION_SEND -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
            else -> emptyList()
        }
        if (incomingUris.isEmpty()) return IncomingRouteResult.NotHandled
        return routeUrisToUploadTarget(incomingUris, applyExtensionFilter = true)
    }

    private fun routeUrisToUploadTarget(
        uris: List<Uri>,
        applyExtensionFilter: Boolean,
    ): IncomingRouteResult {
        return when (val decision = pickerSelectionRouter.decide(uris, applyExtensionFilter)) {
            PickerRouteDecision.NoSelection -> IncomingRouteResult.NotHandled
            is PickerRouteDecision.RejectedOnly -> {
                invalidFilesDialogNamesState.value = decision.rejectedFileNames
                IncomingRouteResult.Handled
            }
            is PickerRouteDecision.SingleFile -> {
                invalidFilesDialogNamesState.value = decision.rejectedFileNames.takeIf { it.isNotEmpty() }
                viewModel.onFileChosen(decision.uri)
                IncomingRouteResult.Handled
            }
            is PickerRouteDecision.MultiFile -> {
                startActivity(
                    MultiUploadNavigation.createIntent(
                        context = this,
                        supportedUris = decision.uris,
                        rejectedFileNames = decision.rejectedFileNames
                    )
                )
                finish()
                IncomingRouteResult.Finished
            }
        }
    }

    private data class IncomingRouteResult(
        val handled: Boolean,
        val finishedActivity: Boolean,
    ) {
        companion object {
            val NotHandled = IncomingRouteResult(handled = false, finishedActivity = false)
            val Handled = IncomingRouteResult(handled = true, finishedActivity = false)
            val Finished = IncomingRouteResult(handled = true, finishedActivity = true)
        }
    }
}
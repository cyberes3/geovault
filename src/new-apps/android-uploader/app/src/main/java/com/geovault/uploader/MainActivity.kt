package com.geovault.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.data.FileMetadataRepository
import com.geovault.uploader.domain.PickerRouteDecision
import com.geovault.uploader.domain.PickerSelectionRouter
import com.geovault.uploader.navigation.MultiUploadNavigation
import com.geovault.uploader.presentation.MainScreenViewModel
import com.geovault.uploader.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels()
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
        if (routeIncomingIntentToUploadTarget(intent)) return
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize(intent)
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.oauthUrl) {
                    val oauthUrl = state.oauthUrl
                    if (!oauthUrl.isNullOrBlank()) {
                        GeovaultAuthManager.launchOAuthInBrowser(this@MainActivity, oauthUrl)
                    }
                }
                MainScreen(
                    state = state,
                    invalidFilesDialogNames = invalidFilesDialogNamesState.value,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                    onAuthConnect = viewModel::connectAuth,
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
                    onDismissUpdatePrompt = viewModel::clearUpdatePrompt,
                    onOpenUpdateUrl = {
                        val url = state.updatePromptUrl ?: return@MainScreen
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (routeIncomingIntentToUploadTarget(intent)) return
        viewModel.initialize(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onOauthUrlConsumed()
    }

    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
    }

    private fun routeIncomingIntentToUploadTarget(intent: Intent?): Boolean {
        val incomingUris = when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            Intent.ACTION_SEND -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
            else -> emptyList()
        }
        if (incomingUris.isEmpty()) return false
        routeUrisToUploadTarget(incomingUris, applyExtensionFilter = false)
        return incomingUris.size > 1
    }

    private fun routeUrisToUploadTarget(uris: List<Uri>, applyExtensionFilter: Boolean) {
        when (val decision = pickerSelectionRouter.decide(uris, applyExtensionFilter)) {
            PickerRouteDecision.NoSelection -> Unit
            is PickerRouteDecision.RejectedOnly -> {
                invalidFilesDialogNamesState.value = decision.rejectedFileNames
            }
            is PickerRouteDecision.SingleFile -> {
                invalidFilesDialogNamesState.value = decision.rejectedFileNames.takeIf { it.isNotEmpty() }
                viewModel.onFileChosen(decision.uri)
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
            }
        }
    }
}
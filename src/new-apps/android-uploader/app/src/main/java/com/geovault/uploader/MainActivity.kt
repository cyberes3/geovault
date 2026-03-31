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
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.presentation.MainScreenViewModel
import com.geovault.uploader.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels()
    private lateinit var chooseFileLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chooseFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.onFileChosen(uri)
            }
        }
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
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                    onAuthConnect = viewModel::connectAuth,
                    onChooseFileClick = {
                        chooseFileLauncher.launch(
                            arrayOf(
                                "application/vnd.google-earth.kml+xml",
                                "application/vnd.google-earth.kmz",
                                "application/gpx+xml"
                            )
                        )
                    },
                    onFilenameChanged = viewModel::onFilenameChanged,
                    onUploadClick = {
                        viewModel.uploadCurrentFile(onSuccessClose = { finish() })
                    },
                    onCloseClick = { finish() },
                    onDismissImportant = viewModel::clearImportantMessage,
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
}
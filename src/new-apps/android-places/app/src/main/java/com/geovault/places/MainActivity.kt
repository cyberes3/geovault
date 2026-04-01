package com.geovault.places

import android.content.Intent
import android.os.Bundle
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
import com.geovault.places.presentation.MainScreenViewModel
import com.geovault.places.ui.MainScreen

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OAUTH_ERROR = "oauth_error"
    }

    private val viewModel: MainScreenViewModel by viewModels()

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onHostResumed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(this)
        viewModel.initialize()
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.oauthUrl) {
                    state.oauthUrl?.let { GeovaultAuthManager.launchOAuthInBrowser(this@MainActivity, it) }
                }
                LaunchedEffect(Unit) {
                    intent.getStringExtra(EXTRA_OAUTH_ERROR)?.let {
                        viewModel.clearSnackbar()
                    }
                }
                MainScreen(
                    state = state,
                    onSearchChanged = viewModel::onSearchChanged,
                    onAuthServerUrlChanged = viewModel::onAuthServerUrlChanged,
                    onAuthConnect = viewModel::connectAuth,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onRefresh = viewModel::refreshNow,
                    onAddPlace = { editLauncher.launch(Intent(this, PlaceEditActivity::class.java)) },
                    onOpenMap = { startActivity(Intent(this, MapActivity::class.java)) },
                    onEditPlace = { feature ->
                        val i = Intent(this, PlaceEditActivity::class.java)
                        i.putExtra("feature", feature)
                        editLauncher.launch(i)
                    },
                    onNavigatePlace = { feature ->
                        val url = com.geovault.places.di.PlacesAppServices.from(application)
                            .navigationRepository()
                            .buildMapsSearchUrl(feature)
                        if (url != null) {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            com.geovault.places.di.PlacesAppServices.from(application)
                                .navigationRepository()
                                .trackNavigation(feature, GeovaultAuthManager.getServerUrl(this))
                        }
                    },
                    onDismissSnackbar = viewModel::clearSnackbar,
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onOauthUrlConsumed()
    }
}
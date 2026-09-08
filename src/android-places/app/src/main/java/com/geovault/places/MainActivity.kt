package com.geovault.places

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.geovault.common.auth.GeoVaultAccountViewModel
import com.geovault.common.auth.GeoVaultAuthExtras
import com.geovault.common.ui.auth.GeoVaultAuthHost
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.ui.PlacesApp

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OAUTH_ERROR = GeoVaultAuthExtras.OAUTH_ERROR_EXTRA_KEY
        const val EXTRA_SHOW_EXPORT_SAVED_MESSAGE = "show_export_saved_message"
    }

    private val accountViewModel: GeoVaultAccountViewModel by viewModels {
        GeoVaultAccountViewModel.factory(PlacesAppServices.from(application).initialAuthController())
    }
    private val clipboardCopyHelper: ClipboardCopyHelper by lazy { ClipboardCopyHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        GeoVaultAuthHost.installSplash(
            this,
            (application as PlacesApplication).bootstrap.isReady,
        )
        super.onCreate(savedInstanceState)
        GeoVaultAuthHost.onCreate(this, accountViewModel)
        clipboardCopyHelper.prewarm()
        setContent {
            GeoVaultTheme {
                PlacesApp(
                    accountViewModel = accountViewModel,
                    clipboardCopyHelper = clipboardCopyHelper,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GeoVaultAuthHost.onNewIntent(intent, accountViewModel)
    }

    override fun onResume() {
        super.onResume()
        val showExportToast = intent?.getBooleanExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, false) == true ||
            PlacesApplication.consumePendingExportSavedToast()
        if (showExportToast) {
            intent?.removeExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE)
            Toast.makeText(this, "Saved places exported to Files -> Downloads", Toast.LENGTH_SHORT).show()
        }
        GeoVaultAuthHost.onResume(accountViewModel)
    }

    override fun onStop() {
        super.onStop()
        GeoVaultAuthHost.onStop(accountViewModel)
    }
}

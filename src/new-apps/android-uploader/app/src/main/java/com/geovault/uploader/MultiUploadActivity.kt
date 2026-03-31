package com.geovault.uploader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.uploader.presentation.QueueUploadViewModel
import com.geovault.uploader.ui.MultiUploadScreen

class MultiUploadActivity : ComponentActivity() {
    private val viewModel: QueueUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeoVaultSystemBars.applyAppChrome(activity = this)
        viewModel.initialize(intent)
        setContent {
            GeoVaultTheme {
                val state by viewModel.state.collectAsState()
                MultiUploadScreen(
                    state = state,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onRename = viewModel::rename,
                    onUploadClick = viewModel::startUpload,
                    onCancelClick = {
                        if (state.isUploading) {
                            viewModel.cancelUpload()
                        } else {
                            finish()
                        }
                    }
                )
            }
        }
    }
}

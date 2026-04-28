package com.geovault.places

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.GeoVaultTopTitleBarDefaults
import com.geovault.common.ui.theme.GeoVaultTheme

class DescriptionViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        setContent {
            GeoVaultTheme {
                DescriptionScreen(title = title, body = body, onClose = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESCRIPTION = "description"
    }
}

@Composable
private fun DescriptionScreen(title: String, body: String, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = if (title.isBlank()) "Description" else title,
                rightActions = listOf(GeoVaultTopTitleBarDefaults.closeAction(onClick = onClose))
            )
        }
    ) { padding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(body)
            }
        }
    }
}

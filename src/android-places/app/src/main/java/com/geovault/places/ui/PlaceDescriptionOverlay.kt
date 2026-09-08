package com.geovault.places.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler

data class PlaceDescriptionArgs(
    val title: String,
    val body: String,
)

@Composable
fun PlaceDescriptionOverlay(
    args: PlaceDescriptionArgs,
    onClose: () -> Unit,
) {
    GeoVaultRegisterBackHandler(
        canGoBack = { true },
        onBack = {
            onClose()
            true
        },
    )
    GeoVaultSubViewScaffold(
        title = args.title.ifBlank { "Description" },
        onClose = onClose,
        onLeaveComposition = onClose,
    ) { padding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(args.body)
            }
        }
    }
}

package com.geovault.places.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.places.model.Feature
import com.geovault.places.presentation.MainScreenState

@Composable
fun MainScreen(
    state: MainScreenState,
    onSearchChanged: (String) -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onAddPlace: () -> Unit,
    onOpenMap: () -> Unit,
    onEditPlace: (Feature) -> Unit,
    onNavigatePlace: (Feature) -> Unit,
    onDismissSnackbar: () -> Unit,
) {
    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "Places",
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(onOpenSettings = onOpenSettings)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            GeoVaultAuthGate(
                isAuthenticated = state.isAuthenticated,
                serverUrl = state.serverUrl,
                onServerUrlChanged = onAuthServerUrlChanged,
                onConnect = onAuthConnect,
                isConnecting = state.isConnecting,
            ) {
                GeoVaultInput(
                    value = state.searchQuery,
                    onValueChange = onSearchChanged,
                    label = "Search Places",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeoVaultPrimaryButton(
                        text = if (state.isRefreshing) "Syncing..." else "Sync",
                        enabled = !state.isRefreshing,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )
                    GeoVaultPrimaryButton(
                        text = "Add",
                        onClick = onAddPlace,
                        modifier = Modifier.weight(1f)
                    )
                    GeoVaultPrimaryButton(
                        text = "Map",
                        onClick = onOpenMap,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                if (state.offline.isNotEmpty()) {
                    SectionHeader("Waiting To Sync")
                    FeatureList(
                        features = state.offline,
                        onEditPlace = onEditPlace,
                        onNavigatePlace = onNavigatePlace,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                SectionHeader("Saved Places")
                if (state.saved.isEmpty() && state.offline.isEmpty()) {
                    Text("No places yet.", style = MaterialTheme.typography.body2)
                } else {
                    FeatureList(
                        features = state.saved,
                        onEditPlace = onEditPlace,
                        onNavigatePlace = onNavigatePlace,
                    )
                }
            }
        }
    }

    GeoVaultSnackbarHost(
        model = state.snackbar,
        onDismiss = onDismissSnackbar,
        onAction = { _ -> onDismissSnackbar() },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.subtitle1)
    Divider(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun FeatureList(
    features: List<Feature>,
    onEditPlace: (Feature) -> Unit,
    onNavigatePlace: (Feature) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(features) { feature ->
            val title = feature.properties.name ?: "(unnamed)"
            val desc = feature.properties.description.orEmpty()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditPlace(feature) }
                    .padding(vertical = 4.dp)
            ) {
                Text(title, style = MaterialTheme.typography.subtitle1)
                if (desc.isNotBlank()) {
                    Text(desc, style = MaterialTheme.typography.body2)
                }
                Text(
                    text = "Navigate",
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.clickable { onNavigatePlace(feature) }
                )
            }
            Divider()
        }
    }
}

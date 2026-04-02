package com.geovault.tracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
import com.geovault.tracker.presentation.MainScreenState

@Composable
fun MainScreen(
    state: MainScreenState,
    onOpenSettings: () -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(TrackerTab.HOME.name) }
    val bottomDestinations = remember {
        listOf(
            GeoVaultBottomNavDestination(
                id = TrackerTab.HOME.name,
                label = "Home",
                icon = Icons.Default.Home,
            ),
            GeoVaultBottomNavDestination(
                id = TrackerTab.MAP.name,
                label = "Map",
                icon = Icons.Default.Map,
            ),
            GeoVaultBottomNavDestination(
                id = TrackerTab.TRACKERS.name,
                label = "Trackers",
                icon = Icons.AutoMirrored.Filled.List,
            ),
            GeoVaultBottomNavDestination(
                id = TrackerTab.SHARED.name,
                label = "Shared",
                icon = Icons.Default.People,
            ),
        )
    }

    GeoVaultBottomNavScaffold(
        destinations = bottomDestinations,
        selectedDestinationId = selectedTab,
        onDestinationSelected = { selectedTab = it.id },
        modifier = Modifier,
    ) { destination ->
        when (destination.id) {
            TrackerTab.HOME.name -> {
                HomeScreen(
                    isAuthenticated = state.isAuthenticated,
                    serverUrl = state.serverUrl,
                    onAuthServerUrlChanged = onAuthServerUrlChanged,
                    onAuthConnect = onAuthConnect,
                    isConnecting = state.isConnecting,
                    onOpenSettings = onOpenSettings,
                )
            }

            TrackerTab.MAP.name -> {
                MapScreen(
                    isAuthenticated = state.isAuthenticated,
                    serverUrl = state.serverUrl,
                    onAuthServerUrlChanged = onAuthServerUrlChanged,
                    onAuthConnect = onAuthConnect,
                    isConnecting = state.isConnecting,
                    onOpenSettings = onOpenSettings,
                )
            }

            TrackerTab.TRACKERS.name -> {
                TrackersScreen(
                    isAuthenticated = state.isAuthenticated,
                    serverUrl = state.serverUrl,
                    onAuthServerUrlChanged = onAuthServerUrlChanged,
                    onAuthConnect = onAuthConnect,
                    isConnecting = state.isConnecting,
                    onOpenSettings = onOpenSettings,
                )
            }

            TrackerTab.SHARED.name -> {
                SharedScreen(
                    isAuthenticated = state.isAuthenticated,
                    serverUrl = state.serverUrl,
                    onAuthServerUrlChanged = onAuthServerUrlChanged,
                    onAuthConnect = onAuthConnect,
                    isConnecting = state.isConnecting,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

private enum class TrackerTab {
    HOME,
    MAP,
    TRACKERS,
    SHARED,
}

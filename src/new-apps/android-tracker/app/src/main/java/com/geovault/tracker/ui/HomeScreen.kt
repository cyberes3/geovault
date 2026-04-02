package com.geovault.tracker.ui

import androidx.compose.runtime.Composable

@Composable
fun HomeScreen(
    isAuthenticated: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
) {
    TrackerTabPlaceholderScreen(
        title = "GeoVault Tracker",
        placeholderText = "Home",
        isAuthenticated = isAuthenticated,
        serverUrl = serverUrl,
        onAuthServerUrlChanged = onAuthServerUrlChanged,
        onAuthConnect = onAuthConnect,
        isConnecting = isConnecting,
        onOpenSettings = onOpenSettings,
    )
}

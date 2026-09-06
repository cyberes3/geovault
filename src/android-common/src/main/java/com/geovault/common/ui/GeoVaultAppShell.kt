package com.geovault.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import com.geovault.common.ui.components.GeoVaultBottomNavDestination
import com.geovault.common.ui.components.GeoVaultBottomNavScaffold
import com.geovault.common.ui.components.GeoVaultSubViewHostActiveProvider
import kotlinx.coroutines.delay

/**
 * App-level chrome: retained bottom-nav tabs, overlay z-order, and a snackbar slot.
 *
 * Visited tabs stay in composition so re-selecting a tab is instant. [alwaysComposedTabIds]
 * stay composed even before the first visit (for example a persistent map). Inactive tabs
 * are hidden with alpha and lose input via z-order.
 */
@Composable
fun GeoVaultAppShell(
    destinations: List<GeoVaultBottomNavDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (GeoVaultBottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    overlayNavBarChrome: Boolean = false,
    alwaysComposedTabIds: Set<String> = emptySet(),
    prewarmTabIds: Collection<String> = emptyList(),
    prewarmEnabled: Boolean = false,
    prewarmDelayMillis: Long = 1_200L,
    overlay: @Composable BoxScope.() -> Unit = {},
    snackbarLayer: @Composable BoxScope.() -> Unit = {},
    tabContent: @Composable BoxScope.(tabId: String, isActive: Boolean) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        GeoVaultBottomNavScaffold(
            destinations = destinations,
            selectedDestinationId = selectedDestinationId,
            overlayNavBarChrome = overlayNavBarChrome,
            onDestinationSelected = onDestinationSelected,
            modifier = Modifier.fillMaxSize(),
        ) { _ ->
            var visitedTabs by remember { mutableStateOf(setOf(selectedDestinationId)) }
            LaunchedEffect(selectedDestinationId) {
                if (selectedDestinationId !in visitedTabs) {
                    visitedTabs = visitedTabs + selectedDestinationId
                }
            }
            LaunchedEffect(prewarmEnabled, prewarmTabIds.joinToString()) {
                if (!prewarmEnabled) return@LaunchedEffect
                delay(prewarmDelayMillis)
                val toAdd = prewarmTabIds.filterNot { it in visitedTabs }
                if (toAdd.isNotEmpty()) {
                    visitedTabs = visitedTabs + toAdd
                }
            }
            val composedTabs = visitedTabs + selectedDestinationId + alwaysComposedTabIds
            Box(modifier = Modifier.fillMaxSize()) {
                destinations.forEach { destination ->
                    val tabId = destination.id
                    if (tabId !in composedTabs) return@forEach
                    val isActive = tabId == selectedDestinationId && !overlayNavBarChrome
                    GeoVaultSubViewHostActiveProvider(isActive = isActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (isActive) 1f else 0f)
                                .zIndex(if (isActive) 1f else 0f),
                        ) {
                            tabContent(tabId, isActive)
                        }
                    }
                }
            }
        }
        overlay()
        snackbarLayer()
    }
}

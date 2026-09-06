package com.geovault.common.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.geovault.common.ui.components.GeoVaultOverlayViewModelStoreOwner
import com.geovault.common.ui.components.GeoVaultSubViewScaffold

/**
 * Single-bar chrome for shell overlays such as Settings.
 *
 * Settings is not a tab: it sits above the current view. This scaffold is the compact
 * "<-Title  X" dismiss strip only — it does not stack a branded title bar on top.
 */
@Composable
fun GeoVaultShellOverlayScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    overlayEntryId: String = "settings",
    closeContentDescription: String = "Close",
    closeTooltip: String? = null,
    headerExtras: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    backgroundColor: Color = MaterialTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    GeoVaultOverlayViewModelStoreOwner(entryId = overlayEntryId) {
        GeoVaultSubViewScaffold(
            title = title,
            onClose = onClose,
            modifier = modifier,
            closeContentDescription = closeContentDescription,
            closeTooltip = closeTooltip,
            headerExtras = headerExtras,
            bottomBar = bottomBar,
            backgroundColor = backgroundColor,
            content = content,
        )
    }
}

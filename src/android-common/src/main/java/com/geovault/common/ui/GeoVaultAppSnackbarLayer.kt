package com.geovault.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.snackbar.GeoVaultSnackbarOverlayDefaults
import com.geovault.common.ui.update.GeoVaultUpdateAvailableSnackbarHost
import com.geovault.common.update.VersionCheckResult

/**
 * App-level snackbar stack: the primary host plus the optional update-available host.
 *
 * The update bar sits above the primary bar using the measured primary height instead of a
 * copied offset.
 */
@Composable
fun GeoVaultAppSnackbarLayer(
    snackbar: GeoVaultSnackbarModel?,
    onDismissSnackbar: () -> Unit,
    update: VersionCheckResult.UpdateAvailable?,
    onDismissUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    onSnackbarAction: (actionId: String) -> Unit = {},
) {
    val density = LocalDensity.current
    var primaryBarHeightPx by remember { mutableIntStateOf(0) }
    val stackBottomInset = if (snackbar == null || primaryBarHeightPx == 0) {
        0.dp
    } else {
        with(density) { primaryBarHeightPx.toDp() } + GeoVaultSnackbarOverlayDefaults.HostEdgePadding
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (snackbar != null) {
            GeoVaultSnackbarHost(
                model = snackbar,
                onDismiss = onDismissSnackbar,
                onAction = onSnackbarAction,
                modifier = GeoVaultSnackbarOverlayDefaults.hostModifier,
                onBarHeightChanged = { primaryBarHeightPx = it },
            )
        }
        GeoVaultUpdateAvailableSnackbarHost(
            update = update,
            onDismiss = onDismissUpdate,
            stackBottomInset = stackBottomInset,
        )
    }
}

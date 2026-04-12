package com.geovault.common.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.ui.snackbar.GeoVaultSnackbarOverlayDefaults
import com.geovault.common.update.CustomTabReleasePageLauncher
import com.geovault.common.update.ReleasePageLauncher
import com.geovault.common.update.UpdateAvailablePromptComposer

/**
 * Bottom snackbar for “new release available”, including opening the release page in a Custom Tab.
 * [model] and [releaseUrl] are expected to be set together; if either is null/blank, nothing is shown.
 */
@Composable
fun GeoVaultUpdateAvailableSnackbarHost(
    model: GeoVaultSnackbarModel?,
    releaseUrl: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = GeoVaultSnackbarOverlayDefaults.hostModifier,
    stackBottomInset: Dp = 0.dp,
) {
    if (model == null || releaseUrl.isNullOrBlank()) return
    val context = LocalContext.current
    val releaseLauncher: ReleasePageLauncher = remember(context) { CustomTabReleasePageLauncher(context) }
    GeoVaultSnackbarHost(
        model = model,
        onDismiss = onDismiss,
        onAction = { actionId ->
            if (actionId == UpdateAvailablePromptComposer.ACTION_OPEN_RELEASE) {
                releaseLauncher.openReleasePage(releaseUrl)
            }
        },
        modifier = modifier,
        stackBottomInset = stackBottomInset,
    )
}

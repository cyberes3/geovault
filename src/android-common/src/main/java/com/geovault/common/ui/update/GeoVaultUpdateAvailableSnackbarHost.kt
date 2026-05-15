package com.geovault.common.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.R
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.snackbar.GeoVaultSnackbarOverlayDefaults
import com.geovault.common.update.CustomTabReleasePageLauncher
import com.geovault.common.update.ReleasePageLauncher
import com.geovault.common.update.UpdateAvailablePromptComposer
import com.geovault.common.update.VersionCheckResult

/**
 * Bottom snackbar for “new release available”; snackbar action opens an in-library details dialog
 * (install with download progress, view release in a Custom Tab, close).
 * [update] is expected to be non-null only while the prompt should show; clearing it dismisses
 * the snackbar and any open dialog state.
 */
@Composable
fun GeoVaultUpdateAvailableSnackbarHost(
    update: VersionCheckResult.UpdateAvailable?,
    onDismiss: () -> Unit,
    modifier: Modifier = GeoVaultSnackbarOverlayDefaults.hostModifier,
    stackBottomInset: Dp = 0.dp,
) {
    if (update == null) return
    val context = LocalContext.current
    val releaseLauncher: ReleasePageLauncher = remember(context) { CustomTabReleasePageLauncher(context) }
    val releaseLauncherState by rememberUpdatedState(releaseLauncher)
    val snackMessage = stringResource(R.string.gv_update_snackbar_message, update.appName, update.versionLabel)
    val detailsLabel = stringResource(R.string.gv_update_snackbar_action_details)
    val model = remember(update.releaseCommitSha, snackMessage, detailsLabel) {
        UpdateAvailablePromptComposer.modelForUpdateAvailable(update, snackMessage, detailsLabel)
    }
    var detailsDialogOpen by remember(update.releaseCommitSha) { mutableStateOf(false) }
    val onDismissState by rememberUpdatedState(onDismiss)

    DisposableEffect(update) {
        onDispose {
            detailsDialogOpen = false
        }
    }

    val snackbarModel = if (detailsDialogOpen) null else model

    GeoVaultSnackbarHost(
        model = snackbarModel,
        onDismiss = onDismissState,
        onAction = { actionId ->
            if (actionId == UpdateAvailablePromptComposer.ACTION_OPEN_UPDATE_DETAILS) {
                detailsDialogOpen = true
            }
        },
        modifier = modifier,
        stackBottomInset = stackBottomInset,
    )

    if (detailsDialogOpen) {
        GeoVaultAppUpdateAvailableDialog(
            update = update,
            onDismissRequest = {
                detailsDialogOpen = false
                onDismissState()
            },
            onOpenReleaseInBrowser = { url -> releaseLauncherState.openReleasePage(url) },
        )
    }
}

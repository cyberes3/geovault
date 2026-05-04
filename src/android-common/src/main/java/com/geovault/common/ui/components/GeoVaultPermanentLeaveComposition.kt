package com.geovault.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.ui.navigation.findComponentActivity

/**
 * Runs [onLeave] only when the composable permanently leaves the host composition.
 *
 * Android tears down the composition while an Activity is handling a configuration change. That
 * is a transient rebuild, not a user dismissal, so components must not clear host-owned overlay
 * state from their dispose callbacks during that window.
 */
@Composable
fun GeoVaultOnPermanentLeaveComposition(
    onLeave: (() -> Unit)?,
) {
    val activity = LocalContext.current.findComponentActivity()
    val leaveState = rememberUpdatedState(onLeave)
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                leaveState.value?.invoke()
            }
        }
    }
}

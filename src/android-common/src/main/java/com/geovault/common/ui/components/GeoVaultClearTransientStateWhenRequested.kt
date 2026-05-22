package com.geovault.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Invokes [onClear] once when [shouldClear] transitions from `false` to `true`.
 *
 * Edge detection avoids re-running [onClear] on every recomposition while the clear
 * condition stays active (e.g. the user remains off the Map tab or an overlay stays open).
 */
@Composable
fun GeoVaultClearTransientStateWhenRequested(
    shouldClear: Boolean,
    onClear: () -> Unit,
) {
    var wasClearRequested by remember { mutableStateOf(false) }
    LaunchedEffect(shouldClear) {
        if (!wasClearRequested && shouldClear) {
            onClear()
        }
        wasClearRequested = shouldClear
    }
}

package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.geovault.tracker.params.TrackerParamsRouteArgs

data class TrackerParamsOverlayState(
    val args: TrackerParamsRouteArgs,
    val onDismiss: () -> Unit,
)

val LocalTrackerParamsOverlay = compositionLocalOf<TrackerParamsOverlayState?> { null }

@Composable
fun TrackerParamsOverlayLayer() {
    val overlay = LocalTrackerParamsOverlay.current ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(4f),
    ) {
        key(overlay.args.trackerId) {
            TrackerParamsScreen(
                args = overlay.args,
                onDismiss = overlay.onDismiss,
            )
        }
    }
}

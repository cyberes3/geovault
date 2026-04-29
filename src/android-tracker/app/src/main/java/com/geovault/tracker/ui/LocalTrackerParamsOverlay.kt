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

/**
 * `true` for the bottom-nav tab that is currently visible to the user. Provided by
 * [com.geovault.tracker.ui.MainScreen] around each kept-composed tab so the per-tab
 * [TrackerParamsOverlayLayer] only mounts inside the active tab — otherwise every
 * kept-composed tab would simultaneously stack a copy of the params overlay on top
 * of its own (invisible) chrome.
 */
val LocalTrackerTabIsActive = compositionLocalOf { false }

/**
 * Per-tab params overlay slot. Sits inside the host tab's content body (under the tab's
 * own [com.geovault.common.ui.components.GeoVaultTopTitleBar] and above its bottom-nav
 * row) so the params sub-view appears within the same chrome the user is already
 * looking at instead of full-screening over both bars.
 *
 * Inactive kept-composed tabs early-return via [LocalTrackerTabIsActive] — without this
 * gate every tab in the visited set would mount the overlay simultaneously when
 * [LocalTrackerParamsOverlay] is non-null.
 */
@Composable
fun TrackerParamsOverlayLayer() {
    if (!LocalTrackerTabIsActive.current) return
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

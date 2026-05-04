package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.geovault.common.ui.modifier.geoVaultStableNavigationBarsPadding

private val LocalGeoVaultSubViewHostIsActive = staticCompositionLocalOf { true }

/**
 * Marks whether retained sub-view hosts, such as cached bottom-nav tabs, are currently visible.
 *
 * Retained hosts keep inactive tabs in composition for performance, so disposal-based
 * leave handling never runs when the user switches tabs. Wrap each retained host with this
 * provider so [GeoVaultSubViewScaffold] can treat host inactivity as the same kind of navigation
 * boundary as leaving composition.
 */
@Composable
fun GeoVaultSubViewHostActiveProvider(
    isActive: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGeoVaultSubViewHostIsActive provides isActive,
        content = content,
    )
}

/**
 * Standard chrome for a dismissible sub-view that is presented below the host's branded
 * [GeoVaultTopTitleBar]. Every edit form, detail screen, filter/import overlay, and settings
 * sub-page should render through this scaffold so sub-view chrome has exactly one source of
 * truth. Hosts must lay this out **edge-to-edge** in the available viewport (no floating
 * modal card, scrim-with-inset sheet, or partial-height panel around this scaffold).
 *
 * The chrome is a single compact "<-Title  X" dismiss strip on top of the body; it never
 * tries to swap the host's branded tab bar out (the host's title bar is expected to remain
 * visible above this scaffold). This matches the survey app's "shell title bar always
 * visible, sub-views render in-body with a compact dismiss strip" pattern.
 *
 * @param onLeaveComposition When non-null, invoked if this scaffold **permanently** leaves the
 *   composition (for example the host navigates to another tab). Use the same callback the
 *   host would use for an unconditional dismiss (clearing VM or local overlay state). Pass
 *   `null` when this composable is only **swapped** for another phase of the same flow so
 *   leaving composition must not dismiss (for example switching internal overlay modes).
 *   Do not default this to [onClose]: guarded close flows should pass a force-dismiss here.
 * @param onHostInactive Invoked when a retained host marks this sub-view's host inactive. Defaults
 *   to [onLeaveComposition], which is the common "force dismiss this sub-view" callback.
 * @param dismissOnHostInactive Set false only for a sub-view that intentionally survives host
 *   inactivity; internal child phases should usually keep [onLeaveComposition] / [onHostInactive]
 *   null instead.
 * @param modifier Applied to the outer [Scaffold]; use this for [statusBarsPadding] when
 *   presenting the scaffold as a root overlay.
 * @param headerExtras Optional extra chrome placed directly under the compact bar (e.g. a
 *   tab bar or divider). Runs in a [ColumnScope] inside the top bar.
 * @param bottomBar Optional bottom chrome (save/done buttons, action rows, etc.).
 * @param backgroundColor Scaffold background; defaults to the theme background. Override
 *   with `MaterialTheme.colors.surface` for sub-views that sit over an elevated surface.
 * @param content Body of the sub-view; receives the scaffold's `innerPadding`.
 */
@Composable
fun GeoVaultSubViewScaffold(
    title: String,
    onClose: () -> Unit,
    onLeaveComposition: (() -> Unit)? = null,
    onHostInactive: (() -> Unit)? = onLeaveComposition,
    dismissOnHostInactive: Boolean = true,
    modifier: Modifier = Modifier,
    closeContentDescription: String = "Close",
    headerExtras: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    backgroundColor: Color = MaterialTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    GeoVaultOnPermanentLeaveComposition(onLeaveComposition)
    GeoVaultDismissOnHostInactive(
        onDismiss = onHostInactive,
        enabled = dismissOnHostInactive,
    )
    Scaffold(
        // Sub-views own nav-bar safe-area for their content/bottomBar so settings, edit forms,
        // station detail, etc. never bleed underneath the system navigation bar even when the
        // surrounding theme/scaffold is intentionally inset-agnostic (see GeoVaultTheme).
        modifier = modifier.geoVaultStableNavigationBarsPadding(),
        backgroundColor = backgroundColor,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                GeoVaultCompactDismissTitleBar(
                    title = title,
                    onClose = onClose,
                    closeContentDescription = closeContentDescription,
                )
                headerExtras?.invoke(this)
            }
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
private fun GeoVaultDismissOnHostInactive(
    onDismiss: (() -> Unit)?,
    enabled: Boolean,
) {
    val hostIsActive = LocalGeoVaultSubViewHostIsActive.current
    val dismissState = rememberUpdatedState(onDismiss)
    LaunchedEffect(hostIsActive, enabled) {
        if (enabled && !hostIsActive) {
            dismissState.value?.invoke()
        }
    }
}

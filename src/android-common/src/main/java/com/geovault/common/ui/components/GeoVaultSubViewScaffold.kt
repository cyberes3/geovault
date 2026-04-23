package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Standard chrome for a dismissible sub-view that is presented below the shell's branded
 * [GeoVaultTopTitleBar]. Every edit form, detail screen, filter/import overlay, and settings
 * sub-page should render through this scaffold so sub-view chrome has exactly one source of
 * truth.
 *
 * Internally this composes the shared [GeoVaultCompactDismissTitleBar] (the only compact
 * dismiss bar in the codebase) inside a Material [Scaffold]'s `topBar`. If [headerExtras] is
 * provided, the compact bar and the extras share a [Column] inside the top bar slot so
 * additional chrome (tab bar, divider, etc.) can sit immediately below the title strip
 * without leaking layout concerns into the caller.
 *
 * Sibling surfaces:
 * - [GeoVaultTopTitleBar] — top-level destination (branded bar).
 * - [GeoVaultTopTabSurface] with `dismissTitle`/`onDismiss` — dismissible sub-view with tabs.
 *
 * Status-bar insets are caller-controlled via [modifier]. Pass
 * `Modifier.statusBarsPadding()` when the sub-view is rendered as a root overlay that sits
 * over system bars; leave it off when the sub-view is nested inside a parent that already
 * handles insets (the typical case inside a shell Scaffold body).
 *
 * @param title Title shown in the compact dismiss bar.
 * @param onClose Invoked when the user taps the close affordance.
 * @param modifier Applied to the outer [Scaffold]; use this for [statusBarsPadding] when
 *   presenting the scaffold as a root overlay.
 * @param closeContentDescription Accessibility description for the close icon.
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
    modifier: Modifier = Modifier,
    closeContentDescription: String = "Close",
    headerExtras: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    backgroundColor: Color = MaterialTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
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

package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Standard chrome for a dismissible sub-view that is presented below the shell's branded
 * [GeoVaultTopTitleBar]. Every edit form, detail screen, filter/import overlay, and settings
 * sub-page should render through this scaffold so sub-view chrome has exactly one source of
 * truth. Hosts must lay this out **edge-to-edge** in the available viewport (no floating
 * modal card, scrim-with-inset sheet, or partial-height panel around this scaffold).
 *
 * [chromeMode] defaults to [GeoVaultSubViewChromeMode.CompactOnly] for sub-views under an existing
 * branded shell (survey, map, settings). Pass [GeoVaultSubViewChromeMode.WithBrandedTabBar] (see
 * [GeoVaultSubViewTabChrome.withStandardSettingsMenu]) when the tab host coordinates via
 * [LocalGeoVaultIntegratedSubViewBrandedChromeReporter] (see [GeoVaultNavTabShell]).
 *
 * @param onLeaveComposition When non-null, invoked if this scaffold **permanently** leaves the
 *   composition (for example the host navigates to another tab). Use the same callback the
 *   host would use for an unconditional dismiss (clearing VM or local overlay state). Pass
 *   `null` when this composable is only **swapped** for another phase of the same flow so
 *   leaving composition must not dismiss (for example switching internal overlay modes).
 *   Do not default this to [onClose]: guarded close flows should pass a force-dismiss here.
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
    modifier: Modifier = Modifier,
    closeContentDescription: String = "Close",
    headerExtras: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    backgroundColor: Color = MaterialTheme.colors.background,
    chromeMode: GeoVaultSubViewChromeMode = GeoVaultSubViewChromeMode.CompactOnly,
    content: @Composable (PaddingValues) -> Unit,
) {
    val reporter = LocalGeoVaultIntegratedSubViewBrandedChromeReporter.current
    val leave = onLeaveComposition
    if (leave != null) {
        DisposableEffect(leave) {
            onDispose { leave() }
        }
    }
    DisposableEffect(chromeMode) {
        val branded = chromeMode is GeoVaultSubViewChromeMode.WithBrandedTabBar
        if (branded) {
            reporter(true)
        }
        onDispose {
            if (branded) {
                reporter(false)
            }
        }
    }
    val outerModifier = when (chromeMode) {
        is GeoVaultSubViewChromeMode.WithBrandedTabBar -> modifier.fillMaxSize()
        is GeoVaultSubViewChromeMode.CompactOnly -> modifier
    }
    Scaffold(
        modifier = outerModifier,
        backgroundColor = backgroundColor,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (chromeMode) {
                    is GeoVaultSubViewChromeMode.WithBrandedTabBar -> {
                        val b = chromeMode.branded
                        GeoVaultTopTitleBar(
                            title = b.title,
                            subtitle = b.subtitle,
                            backgroundColor = b.resolvedBackgroundColor(),
                            contentColor = b.contentColor,
                            syncSystemStatusBarColor = b.syncSystemStatusBarColor,
                            hideIconActions = b.hideIconActions,
                            rightActions = b.rightActions,
                            actionsContent = b.actionsContent,
                        )
                    }
                    is GeoVaultSubViewChromeMode.CompactOnly -> Unit
                }
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

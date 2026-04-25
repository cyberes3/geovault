package com.geovault.common.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Branded tab chrome that may be composed **inside** [GeoVaultSubViewScaffold] when
 * [GeoVaultSubViewChromeMode.WithBrandedTabBar] is used, stacked above the compact dismiss strip.
 *
 * Field semantics match [GeoVaultTopTitleBar]; null [backgroundColor] uses the theme primary.
 */
data class GeoVaultSubViewBrandedTabBarSpec(
    val title: String,
    val subtitle: String? = null,
    val backgroundColor: Color? = null,
    val contentColor: Color = Color.White,
    val syncSystemStatusBarColor: Boolean = true,
    val hideIconActions: Boolean = false,
    val rightActions: List<TopBarIconAction> = emptyList(),
    val actionsContent: (@Composable (RowScope.() -> Unit))? = null,
)

/**
 * How [GeoVaultSubViewScaffold] paints its [androidx.compose.material.Scaffold] `topBar` slot.
 *
 * - [CompactOnly]: compact dismiss strip (and optional [headerExtras]) — use when no branded
 *   tab bar is integrated (e.g. shell already draws [GeoVaultTopTitleBar], or survey overlays).
 * - [WithBrandedTabBar]: [GeoVaultTopTitleBar] from [branded] **then** the compact strip.
 *   Requires a host that participates in [LocalGeoVaultIntegratedSubViewBrandedChromeReporter]
 *   (see [GeoVaultNavTabShell]) so the outer tab bar is not duplicated.
 */
sealed class GeoVaultSubViewChromeMode {
    data object CompactOnly : GeoVaultSubViewChromeMode()

    data class WithBrandedTabBar(val branded: GeoVaultSubViewBrandedTabBarSpec) : GeoVaultSubViewChromeMode()
}

/**
 * When [GeoVaultSubViewChromeMode.WithBrandedTabBar] is active, the scaffold reports `true` so
 * [GeoVaultNavTabShell] can hide its own [GeoVaultTopTitleBar]. Default is a no-op for hosts
 * that do not wrap tab content (e.g. survey shell).
 */
val LocalGeoVaultIntegratedSubViewBrandedChromeReporter = compositionLocalOf<(Boolean) -> Unit> {
    { }
}

@Composable
internal fun GeoVaultSubViewBrandedTabBarSpec.resolvedBackgroundColor(): Color =
    backgroundColor ?: MaterialTheme.colors.primary

/**
 * Factory for the common tracker-style tab integration: branded [GeoVaultTopTitleBar] fields
 * plus the overflow settings affordance, matching [GeoVaultNavTabShell]'s default top bar.
 */
object GeoVaultSubViewTabChrome {
    @Composable
    fun withStandardSettingsMenu(
        title: String,
        onOpenSettings: () -> Unit,
        settingsMenuEnabled: Boolean = true,
        settingsOverflowTooltip: String? = null,
    ): GeoVaultSubViewChromeMode =
        GeoVaultSubViewChromeMode.WithBrandedTabBar(
            GeoVaultSubViewBrandedTabBarSpec(
                title = title,
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        enabled = settingsMenuEnabled,
                        overflowTooltip = settingsOverflowTooltip,
                    )
                },
            ),
        )
}

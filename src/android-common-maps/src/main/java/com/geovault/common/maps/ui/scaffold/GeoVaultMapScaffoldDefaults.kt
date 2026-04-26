package com.geovault.common.maps.ui.scaffold

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Canonical design tokens for [GeoVaultMapScaffold].
 *
 * Kept as an `object` (not a data class) so the compiler inlines the values and no extra
 * object allocation happens per-frame — the scaffold reads these once per composition.
 *
 * Color tokens are exposed as `@Composable` helpers so the drawer can pick the correct
 * light / dark value without callers threading a theme through the slot API.
 */
object GeoVaultMapScaffoldDefaults {
    /**
     * Programmatic and drag-release snaps between drawer anchors. A snappy critically-damped
     * spring — fast settle, no overshoot, physical feel.
     *
     * Spring (rather than the foundation library's longer default tween) makes anchor changes
     * feel responsive: a row tap or programmatic [GeoVaultMapDrawerState.animateTo] visibly
     * commits within ~250 ms but accelerates non-linearly so it reads as motion instead of a
     * mid-frame jump. `StiffnessMediumLow` is the standard sheet-snap pick used by Compose's
     * own ModalBottomSheet defaults; `DampingRatioNoBouncy` avoids sheet wobble at the anchor.
     *
     * Co-animations driven off the live drawer offset (e.g. the survey route's "pan map to
     * keep selection above sheet" mirror) keep working because they read
     * [GeoVaultMapDrawerState.visibleHeightPx] each frame and react to whatever value the
     * spring is producing — no parallel `animate()` curve needs to be matched.
     */
    val DrawerAnchorSnapSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Visible drawer height when collapsed. Mirrors the old survey app's ~65dp peek. */
    val PeekHeight: Dp = 65.dp

    /** Half-expanded drawer occupies 55% of the container height. */
    const val HalfExpandedFraction: Float = 0.55f

    /** Rounded top radius matching the old survey-app drawer shape. */
    val DrawerCornerRadius: Dp = 28.dp

    /**
     * Stroke width for the map drawer outline. Matches the former survey
     * `pointsBottomSheet` (1.5dp `primary_blue`); the path is clipped so the bottom edge
     * of the full-height sheet is not outlined.
     */
    val DrawerBorderWidth: Dp = 1.5.dp

    /** Same as [GeoVaultColorTokens.MainBlue] (map scaffold bottom sheet). */
    val DrawerBorderColor: Color
        get() = GeoVaultColorTokens.MainBlue

    /**
     * Drop-shadow depth behind the drawer. Matches the old survey-app `MaterialCardView`
     * which rendered with `cardElevation="0dp"` — no shadow fringes against the map.
     */
    val DrawerElevation: Dp = 0.dp

    /**
     * Drag handle dimensions. Sized to match the old survey app's 48dp × 4dp pill so the
     * thumb target is unchanged for existing muscle memory.
     */
    val DragHandleWidth: Dp = 48.dp
    val DragHandleHeight: Dp = 4.dp
    val DragHandleTopPadding: Dp = 12.dp
    val DragHandleBottomPadding: Dp = 4.dp

    /** Height allocated to the drawer header (title + actions row). Tuned for the peek value. */
    val HeaderMinHeight: Dp = 44.dp

    /** Horizontal padding applied inside the header row. */
    val HeaderHorizontalPadding: Dp = 12.dp

    /** Thickness of the hairline drawn under the drawer header, separating it from the body. */
    val HeaderDividerThickness: Dp = 1.dp

    /**
     * Drawer container color. Matches the old survey-app `bottom_sheet_background`
     * (`blue_extra_light` = #F3F6FA) in light mode; uses [GeoVaultColorTokens.Dark.Surface]
     * in dark mode for consistency with the rest of the GeoVault palette (not pure black).
     */
    val DrawerContainerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) {
            GeoVaultColorTokens.Dark.Surface
        } else {
            GeoVaultColorTokens.ListBackground
        }

    /**
     * Drag handle color. Light/dark pair mirrors the old app's `border_light` token
     * (#C4D2ED / #404040) so the handle carries the same muted-blue tone on the pill.
     */
    val DragHandleColor: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) {
            GeoVaultColorTokens.Dark.BorderLight
        } else {
            GeoVaultColorTokens.BorderLight
        }

    /**
     * Thin divider drawn under the drawer header row — the same hairline the old app's
     * `fragment_map.xml` rendered between the title and the points list.
     */
    val HeaderDividerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) {
            GeoVaultColorTokens.Dark.BorderLight
        } else {
            GeoVaultColorTokens.Gray200
        }

    /** Title text color for drawer headers. */
    val HeaderTitleColor: Color get() = GeoVaultColorTokens.MainBlue
    val HeaderActionColor: Color get() = GeoVaultColorTokens.MainBlue
    val TitleChipBackgroundColor: Color get() = GeoVaultColorTokens.Blue100
    val TitleChipContentColor: Color get() = GeoVaultColorTokens.MainBlue
}

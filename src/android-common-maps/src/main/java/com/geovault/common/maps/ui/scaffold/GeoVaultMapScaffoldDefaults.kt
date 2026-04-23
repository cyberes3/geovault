package com.geovault.common.maps.ui.scaffold

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Canonical design tokens for [GeoVaultMapScaffold].
 *
 * Kept as an `object` (not a data class) so the compiler inlines the values and no extra
 * object allocation happens per-frame — the scaffold reads these once per composition.
 */
object GeoVaultMapScaffoldDefaults {
    /** Visible drawer height when collapsed. Mirrors the old survey app's ~65dp peek. */
    val PeekHeight: Dp = 65.dp

    /** Half-expanded drawer occupies 55% of the container height. */
    const val HalfExpandedFraction: Float = 0.55f

    /** Rounded top radius matching the old survey-app drawer shape. */
    val DrawerCornerRadius: Dp = 28.dp

    /** 1 dp hairline on the top edge to separate drawer from map. */
    val DrawerTopBorderWidth: Dp = 1.dp

    /** Shadow elevation behind the drawer; matches Material3 bottom sheet. */
    val DrawerElevation: Dp = 8.dp

    /** Drag handle dimensions. Sized for a comfortable thumb target. */
    val DragHandleWidth: Dp = 36.dp
    val DragHandleHeight: Dp = 4.dp
    val DragHandleTopPadding: Dp = 8.dp
    val DragHandleBottomPadding: Dp = 4.dp

    /** Height allocated to the drawer header (title + actions row). Tuned for the peek value. */
    val HeaderMinHeight: Dp = 44.dp

    /** Horizontal padding applied inside the header row. */
    val HeaderHorizontalPadding: Dp = 12.dp

    /** Default colors derived from [GeoVaultColorTokens]. */
    val DrawerContainerColor: Color get() = GeoVaultColorTokens.Surface
    val DrawerBorderColor: Color get() = GeoVaultColorTokens.BorderLight
    val DragHandleColor: Color get() = GeoVaultColorTokens.Gray300
    val HeaderTitleColor: Color get() = GeoVaultColorTokens.TextPrimary
    val TitleChipBackgroundColor: Color get() = GeoVaultColorTokens.BlueLight
    val TitleChipContentColor: Color get() = GeoVaultColorTokens.PrimaryBlue
}

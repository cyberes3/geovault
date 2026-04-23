package com.geovault.common.maps.ui.scaffold

/**
 * The three discrete resting positions [GeoVaultMapScaffold]'s bottom drawer can rest at.
 *
 * Kept deliberately tiny — anchors correspond 1:1 to the old survey app's
 * `BottomSheetBehavior.STATE_COLLAPSED / STATE_HALF_EXPANDED / STATE_EXPANDED` so users see
 * the same three-snap behaviour.
 */
enum class GeoVaultMapDrawerAnchor {
    /** Header + drag handle only; the map owns the rest of the viewport. */
    Collapsed,

    /** Drawer occupies a configurable fraction of the container height. */
    HalfExpanded,

    /** Drawer fills the available height (minus any top-safe inset the host applies). */
    Expanded,
}

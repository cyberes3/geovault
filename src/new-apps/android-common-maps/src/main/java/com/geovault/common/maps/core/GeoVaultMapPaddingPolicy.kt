package com.geovault.common.maps.core

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Single source of truth for map camera padding.
 *
 * Viewport padding and bounds-fit padding must come from the same policy so camera framing
 * remains stable and content is not unexpectedly over-inset.
 */
class GeoVaultMapPaddingPolicy(
    val includeDefaultFabColumnPadding: Boolean,
    val mapPaddingDp: GeoVaultMapPaddingDp = GeoVaultMapPaddingDp(),
) {
    fun computeViewportPaddingPx(density: Density): DoubleArray {
        fun Dp.orZeroPx(): Double {
            return if (this == Dp.Unspecified) 0.0 else with(density) { toPx().toDouble() }
        }

        if (includeDefaultFabColumnPadding) {
            // Same edge inset on all sides, plus additional right-side reserve for the FAB stack
            // (see GeoVaultMapFabColumn default end margin + fabSize) to keep fitted content clear.
            val edge = with(density) { DEFAULT_MAP_EDGE_PADDING_DP.toPx().toDouble() }
            val fabColumnReserve = with(density) {
                (DEFAULT_MAP_FAB_COLUMN_END_MARGIN_DP + DEFAULT_MAP_FAB_COLUMN_FAB_SIZE_DP).toPx().toDouble()
            }
            val leftExtra = with(density) { DEFAULT_MAP_LEFT_SAFE_EXTRA_DP.toPx().toDouble() }
            val rightExtra = with(density) { DEFAULT_MAP_RIGHT_SAFE_EXTRA_DP.toPx().toDouble() }
            return doubleArrayOf(
                edge + leftExtra + mapPaddingDp.left.orZeroPx(),
                edge + mapPaddingDp.top.orZeroPx(),
                edge + fabColumnReserve + rightExtra + mapPaddingDp.right.orZeroPx(),
                edge + mapPaddingDp.bottom.orZeroPx(),
            )
        }

        return doubleArrayOf(
            mapPaddingDp.left.orZeroPx(),
            mapPaddingDp.top.orZeroPx(),
            mapPaddingDp.right.orZeroPx(),
            mapPaddingDp.bottom.orZeroPx(),
        )
    }

    /**
     * Bounds-fit insets derived from the same viewport policy to avoid divergent framing behavior.
     */
    fun computeBoundsFitPaddingPx(density: Density): IntArray {
        val viewport = computeViewportPaddingPx(density)
        return intArrayOf(
            viewport[0].roundToInt().coerceAtLeast(0),
            viewport[1].roundToInt().coerceAtLeast(0),
            viewport[2].roundToInt().coerceAtLeast(0),
            viewport[3].roundToInt().coerceAtLeast(0),
        )
    }
}

internal val DEFAULT_GEO_VAULT_MAP_PADDING_POLICY = GeoVaultMapPaddingPolicy(
    includeDefaultFabColumnPadding = true,
)

private val DEFAULT_MAP_EDGE_PADDING_DP = 16.dp
private val DEFAULT_MAP_LEFT_SAFE_EXTRA_DP = 8.dp
private val DEFAULT_MAP_RIGHT_SAFE_EXTRA_DP = 12.dp

/** Matches [GeoVaultMapFabColumn] default `Modifier.padding` end inset and FAB width. */
private val DEFAULT_MAP_FAB_COLUMN_END_MARGIN_DP = 16.dp
private val DEFAULT_MAP_FAB_COLUMN_FAB_SIZE_DP = 44.dp

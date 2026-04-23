package com.geovault.common.maps.ui.scaffold

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Returns a vertical [NestedScrollConnection] that couples a scrollable drawer body (e.g. a
 * `LazyColumn`) to the drawer's [AnchoredDraggableState].
 *
 * Adapted from Material3's internal `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection`
 * and simplified to the vertical axis. Keeps the three standard behaviours users expect:
 *
 *  - Dragging the drawer body downward when fully expanded first consumes scroll to collapse
 *    the drawer toward half/peek anchors before the list itself starts scrolling.
 *  - Releasing a fling upward on a partially-expanded drawer commits to the next higher anchor
 *    (the fling is not swallowed by the list).
 *  - Scrolling the list while the drawer is already at its top anchor passes through normally.
 *
 * [onFling] is provided by the scaffold and typically calls `state.settle(velocity)` inside a
 * coroutine scope so the animation is owned by the composition's lifecycle.
 */
internal fun drawerNestedScrollConnection(
    state: AnchoredDraggableState<GeoVaultMapDrawerAnchor>,
    onFling: (velocityPxPerSec: Float) -> Unit,
): NestedScrollConnection = object : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0f && source == NestedScrollSource.UserInput) {
            Offset(x = 0f, y = state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return if (source == NestedScrollSource.UserInput) {
            Offset(x = 0f, y = state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val toFling = available.y
        val currentOffset = runCatching { state.requireOffset() }.getOrNull() ?: return Velocity.Zero
        val minAnchorOffset = state.anchors.minPosition()
        return if (toFling < 0f && currentOffset > minAnchorOffset) {
            onFling(toFling)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        onFling(available.y)
        return available
    }
}

/**
 * Helper to spawn a fling-settle coroutine in the scaffold's scope. Mirrors the snippet every
 * Compose community example uses so the scaffold itself stays free of velocity-plumbing noise.
 *
 * [velocityPxPerSec] is intentionally consumed by the underlying `dispatchRawDelta` feedback
 * loop; we only need to animate to the settled [AnchoredDraggableState.targetValue] here.
 */
internal fun CoroutineScope.settleDrawerOnFling(
    state: AnchoredDraggableState<GeoVaultMapDrawerAnchor>,
    @Suppress("UNUSED_PARAMETER") velocityPxPerSec: Float,
) {
    launch { state.animateTo(state.targetValue) }
}

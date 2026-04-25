package com.geovault.common.maps.ui.scaffold

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Hoistable state for [GeoVaultMapScaffold]'s bottom drawer.
 *
 * Delegates gesture, fling, and snap physics to Compose Foundation's
 * [AnchoredDraggableState] — the officially-recommended building block for multi-stop bottom
 * sheets — and layers map-scaffold-specific affordances on top:
 *
 *  - [currentAnchor] / [targetAnchor] describe the discrete sheet state for feature code.
 *  - [visibleHeightPx] is a single `State<Int>` consumers forward into their
 *    [com.geovault.common.maps.core.GeoVaultMapPaddingPolicy] so the map's camera padding
 *    follows the drawer live during drags and animations.
 *  - [updateAnchors] owns the container-height-aware anchor math so feature code never has
 *    to touch [DraggableAnchors] directly.
 *
 * The class deliberately exposes the underlying [AnchoredDraggableState] via
 * [anchoredDraggableState] so the scaffold itself can attach `Modifier.anchoredDraggable` and
 * nested-scroll connections without re-creating a parallel gesture pipeline.
 *
 * Prefer [rememberGeoVaultMapDrawerState] from feature code; the primary constructor is
 * exposed so unit tests can exercise the anchor math without a Compose environment.
 */
class GeoVaultMapDrawerState internal constructor(
    /** Peek height shown when the drawer rests at [GeoVaultMapDrawerAnchor.Collapsed], in px. */
    val peekHeightPx: Int,
    /** Fraction of the container height occupied by [GeoVaultMapDrawerAnchor.HalfExpanded]. */
    val halfExpandedFraction: Float,
    initialAnchor: GeoVaultMapDrawerAnchor = GeoVaultMapDrawerAnchor.Collapsed,
    /**
     * Animation spec used by [animateTo] (sheet snap-to-anchor).
     *
     * Defaulted to a fixed-duration tween rather than the foundation library's default
     * spring so consumers that need to drive *concurrent* animations (e.g. screen-space
     * map panning to keep a selection visible while the sheet rises) can match this exact
     * curve frame-for-frame. Spring physics have variable settling time and a non-linear
     * progression that can't be replicated by an external coroutine driving MapLibre's
     * `scrollBy` — the result is a visible desync between sheet position and map shift.
     * A deterministic tween makes lockstep co-animation trivial: read [snapAnimationSpec],
     * pass it to `androidx.compose.animation.core.animate`, and both motions step on the
     * same frame clock with identical easing.
     */
    val snapAnimationSpec: AnimationSpec<Float> = DefaultSnapAnimationSpec,
) {
    init {
        require(peekHeightPx >= 0) { "peekHeightPx must be >= 0, was $peekHeightPx" }
        require(halfExpandedFraction in 0f..1f) {
            "halfExpandedFraction must be in [0,1], was $halfExpandedFraction"
        }
    }

    /**
     * Underlying anchored-draggable state. Public so [GeoVaultMapScaffold] can wire
     * `Modifier.anchoredDraggable` and the nested-scroll connection to it — feature code
     * almost never needs to call this directly; prefer [animateTo] / [snapTo].
     */
    val anchoredDraggableState: AnchoredDraggableState<GeoVaultMapDrawerAnchor> =
        AnchoredDraggableState(
            initialValue = initialAnchor,
            // Drag past the geometric midpoint snaps to the next anchor (the standard
            // bottom-sheet idiom that the no-arg `AnchoredDraggableState` constructor uses).
            positionalThreshold = { totalDistance: Float -> totalDistance * 0.5f },
            // No velocity-only snap — drags only commit by passing the positional threshold,
            // matching the previous default. (The decay spec below still handles real flings.)
            velocityThreshold = { 0f },
            snapAnimationSpec = snapAnimationSpec,
            // Fling/decay physics for drag releases. Tap-driven `animateTo` snaps go through
            // `snapAnimationSpec` (above) — this only kicks in when the user actually flings
            // the sheet with a velocity that overshoots the nearest anchor.
            decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
        )

    /**
     * Last container height observed via [updateAnchors], as observable state. The scaffold
     * reads this via [containerHeightPxState] so the drawer Surface re-sizes the instant the
     * first onSizeChanged pass delivers a real container height.
     */
    private val containerHeightPxBackingState = mutableStateOf(0)
    private var containerHeightPxBacking: Int
        get() = containerHeightPxBackingState.value
        set(value) { containerHeightPxBackingState.value = value }

    /** Observable view of [containerHeightPx] — reads participate in recomposition. */
    val containerHeightPxState: State<Int> = containerHeightPxBackingState

    /** Last container height observed via [updateAnchors]. Safe to read at any time. */
    val containerHeightPx: Int get() = containerHeightPxBacking

    /** The anchor the state is currently settled at (updated only after an animation finishes). */
    val currentAnchor: GeoVaultMapDrawerAnchor
        get() = anchoredDraggableState.settledValue

    /**
     * The anchor the state is currently animating towards (equals [currentAnchor] when idle).
     * Read in effects when you need to react the instant the user commits to a new anchor,
     * rather than waiting for the animation to finish.
     */
    val targetAnchor: GeoVaultMapDrawerAnchor
        get() = anchoredDraggableState.targetValue

    /**
     * How many pixels of the drawer are visible right now — header + body clip included.
     *
     * Hosts forward this into [com.geovault.common.maps.core.GeoVaultMapPaddingPolicy] so
     * fit-to-bounds padding and the viewport padding both follow the drawer. The value is
     * clamped to `[peekHeightPx, containerHeightPx]` to tolerate the pre-measurement frame
     * where the container height isn't known yet.
     */
    val visibleHeightPx: State<Int> = derivedStateOf {
        val container = containerHeightPxBacking
        if (container <= 0) {
            peekHeightPx
        } else {
            val offset = runCatching { anchoredDraggableState.requireOffset() }
                .getOrElse { container.toFloat() - peekHeightPx }
                .roundToInt()
                .coerceIn(0, container)
            (container - offset).coerceIn(peekHeightPx.coerceAtMost(container), container)
        }
    }

    /** Smoothly animate the drawer to [anchor]. Cancels any in-flight animation. */
    suspend fun animateTo(anchor: GeoVaultMapDrawerAnchor) {
        anchoredDraggableState.animateTo(anchor)
    }

    /** Snap the drawer to [anchor] without animation. */
    suspend fun snapTo(anchor: GeoVaultMapDrawerAnchor) {
        anchoredDraggableState.snapTo(anchor)
    }

    /**
     * Pixel height the drawer would use if the sheet were settled at [GeoVaultMapDrawerAnchor.HalfExpanded]
     * (same math as [updateAnchors] and [visibleHeightPx]). Exposed for map "keep selection above
     * the sheet" panning while the sheet is still mid-animation, where [visibleHeightPx] still
     * tracks the smaller in-flight value.
     */
    fun halfExpandedSettledVisibleHeightPx(): Int {
        val cTotal = containerHeightPx
        if (cTotal <= 0) return 0
        val c = cTotal.toFloat()
        val collapsedOffset = (c - peekHeightPx).coerceAtLeast(0f)
        val halfOffset = (c * (1f - halfExpandedFraction)).coerceIn(0f, collapsedOffset)
        return (c - halfOffset)
            .roundToInt()
            .coerceIn(peekHeightPx.coerceAtMost(cTotal), cTotal)
    }

    /**
     * Bottom "reserve" for screen-space map panning so a lat/lon stays in the part of the
     * viewport that will be above the sheet's top edge, including the destination for in-flight
     * animations. ([visibleHeightPx] alone under-estimates when [targetAnchor] is half but the
     * drag animation has not finished — the map would be nudged for peek, then the real half
     * would still cover the point.)
     */
    fun mapPanDrawerBottomReservePx(liveVisibleHeightPx: Int): Int {
        return when (targetAnchor) {
            GeoVaultMapDrawerAnchor.HalfExpanded -> maxOf(
                liveVisibleHeightPx,
                halfExpandedSettledVisibleHeightPx(),
            )
            GeoVaultMapDrawerAnchor.Expanded -> maxOf(
                liveVisibleHeightPx,
                containerHeightPx,
            )
            GeoVaultMapDrawerAnchor.Collapsed -> liveVisibleHeightPx
        }
    }

    /**
     * Refresh anchor offsets for a new container height. Called by [GeoVaultMapScaffold] in
     * `onSizeChanged`; feature code does not need to call this directly.
     *
     * Anchor offsets describe the drawer's top-Y position within the container:
     *   Expanded      → 0
     *   HalfExpanded  → container * (1 - halfExpandedFraction)
     *   Collapsed     → container - peekHeightPx
     */
    internal fun updateAnchors(containerHeightPx: Int) {
        containerHeightPxBacking = containerHeightPx
        if (containerHeightPx <= 0) return
        val container = containerHeightPx.toFloat()
        val collapsedOffset = (container - peekHeightPx).coerceAtLeast(0f)
        val halfOffset = (container * (1f - halfExpandedFraction)).coerceIn(0f, collapsedOffset)
        val expandedOffset = 0f
        val newAnchors = DraggableAnchors {
            GeoVaultMapDrawerAnchor.Expanded at expandedOffset
            GeoVaultMapDrawerAnchor.HalfExpanded at halfOffset
            GeoVaultMapDrawerAnchor.Collapsed at collapsedOffset
        }
        anchoredDraggableState.updateAnchors(newAnchors, anchoredDraggableState.targetValue)
    }

    /** Exposed for unit tests only — returns the raw offset px for [anchor], if known. */
    internal fun anchorOffsetForTesting(anchor: GeoVaultMapDrawerAnchor): Float? {
        val container = containerHeightPxBacking
        if (container <= 0) return null
        return when (anchor) {
            GeoVaultMapDrawerAnchor.Expanded -> 0f
            GeoVaultMapDrawerAnchor.HalfExpanded ->
                (container * (1f - halfExpandedFraction)).coerceIn(0f, (container - peekHeightPx).toFloat())
            GeoVaultMapDrawerAnchor.Collapsed -> (container - peekHeightPx).toFloat().coerceAtLeast(0f)
        }
    }
}

/**
 * Default animation spec for [GeoVaultMapDrawerState.animateTo]. A 300 ms tween with the
 * standard Material easing curve. Exposed as a `const val`-style top-level so feature code
 * driving co-animations alongside the sheet (notably the Survey route's "pan map to keep
 * selection visible above drawer" effect) can pass the *same* spec into Compose's
 * `androidx.compose.animation.core.animate` and have both motions step on a single frame
 * clock with identical easing — eliminating the visible desync that resulted from MapLibre
 * `scrollBy` (linear/cubic ease) running concurrently with a default spring sheet animation.
 */
val DefaultSnapAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 300, easing = FastOutSlowInEasing)

/**
 * Compose entry point that wires a [GeoVaultMapDrawerState] with density-sensitive peek height.
 *
 * Keyed on [peekHeight] and [halfExpandedFraction] so a layout change that resizes the header
 * produces a fresh state — mutating those values in-place would invalidate current anchor
 * offsets mid-drag, which we deliberately forbid.
 */
@Composable
fun rememberGeoVaultMapDrawerState(
    peekHeight: Dp = GeoVaultMapScaffoldDefaults.PeekHeight,
    halfExpandedFraction: Float = GeoVaultMapScaffoldDefaults.HalfExpandedFraction,
    initialAnchor: GeoVaultMapDrawerAnchor = GeoVaultMapDrawerAnchor.Collapsed,
    snapAnimationSpec: AnimationSpec<Float> = DefaultSnapAnimationSpec,
): GeoVaultMapDrawerState {
    val density = LocalDensity.current
    val peekPx = with(density) { peekHeight.toPx().roundToInt() }
    return remember(peekPx, halfExpandedFraction) {
        GeoVaultMapDrawerState(
            peekHeightPx = peekPx,
            halfExpandedFraction = halfExpandedFraction,
            initialAnchor = initialAnchor,
            snapAnimationSpec = snapAnimationSpec,
        )
    }
}

/** Test-only constructor — production callers must use [rememberGeoVaultMapDrawerState]. */
internal fun createGeoVaultMapDrawerStateForTest(
    peekHeightPx: Int,
    halfExpandedFraction: Float,
    initialAnchor: GeoVaultMapDrawerAnchor = GeoVaultMapDrawerAnchor.Collapsed,
): GeoVaultMapDrawerState = GeoVaultMapDrawerState(
    peekHeightPx = peekHeightPx,
    halfExpandedFraction = halfExpandedFraction,
    initialAnchor = initialAnchor,
)

/** Test-only helper exposing the internal anchor math. */
internal fun GeoVaultMapDrawerState.updateAnchorsForTest(containerHeightPx: Int) {
    updateAnchors(containerHeightPx)
}

/** Test-only snapshot of the internal anchor offset math. */
internal fun GeoVaultMapDrawerState.testOnlyAnchorOffsetPx(anchor: GeoVaultMapDrawerAnchor): Float? =
    anchorOffsetForTesting(anchor)

package com.geovault.common.maps.ui.scaffold

import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [GeoVaultMapDrawerState].
 *
 * We deliberately exercise the anchor math and the [GeoVaultMapDrawerState.visibleHeightPx]
 * derivation directly — both are pure functions of container height + peek + fraction — and
 * avoid spinning up a Compose test environment. The scaffold's gesture wiring is delegated to
 * [androidx.compose.foundation.gestures.AnchoredDraggableState] (covered by Jetpack tests), so
 * re-testing drag physics here would duplicate what AOSP already verifies.
 */
class GeoVaultMapDrawerStateTest {

    // ---------------------------------------------------------------------
    // Construction / input validation
    // ---------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsNegativePeekHeight() {
        createGeoVaultMapDrawerStateForTest(
            peekHeightPx = -1,
            halfExpandedFraction = 0.5f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsFractionAboveOne() {
        createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = 1.5f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsFractionBelowZero() {
        createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = -0.1f,
        )
    }

    // ---------------------------------------------------------------------
    // Anchor math
    // ---------------------------------------------------------------------

    @Test
    fun anchorOffsets_unknownBeforeContainerMeasured() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 120,
            halfExpandedFraction = 0.5f,
        )

        assertNull(state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Collapsed))
        assertNull(state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.HalfExpanded))
        assertNull(state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Expanded))
    }

    @Test
    fun anchorOffsets_afterUpdate_matchExpectedPositions() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 200,
            halfExpandedFraction = 0.5f,
        )

        state.updateAnchorsForTest(containerHeightPx = 1000)

        assertEquals(0f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Expanded))
        assertEquals(500f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.HalfExpanded))
        assertEquals(800f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Collapsed))
    }

    @Test
    fun halfExpanded_isClampedWhenFractionWouldGoBelowPeek() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 900,
            halfExpandedFraction = 0.5f,
        )

        state.updateAnchorsForTest(containerHeightPx = 1000)

        // collapsed offset = 1000 - 900 = 100, halfExpanded would be 500 but must not go below
        // a position "higher than collapsed" (larger Y) — clamped to collapsed offset so the
        // three-anchor set stays monotonic.
        assertEquals(100f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Collapsed))
        assertEquals(
            100f,
            state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.HalfExpanded),
        )
    }

    @Test
    fun collapsedOffset_neverBelowZero_whenPeekLargerThanContainer() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 1500,
            halfExpandedFraction = 0.5f,
        )

        state.updateAnchorsForTest(containerHeightPx = 1000)

        assertEquals(0f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Collapsed))
        assertEquals(0f, state.testOnlyAnchorOffsetPx(GeoVaultMapDrawerAnchor.Expanded))
    }

    // ---------------------------------------------------------------------
    // visibleHeightPx — the main externally-observed derivation
    // ---------------------------------------------------------------------

    @Test
    fun visibleHeightPx_returnsPeekBeforeContainerKnown() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 150,
            halfExpandedFraction = 0.5f,
        )

        val visible by state.visibleHeightPx
        Snapshot.withMutableSnapshot { /* flush initial reads */ }
        assertEquals(150, visible)
    }

    @Test
    fun visibleHeightPx_collapsed_equalsPeek() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 200,
            halfExpandedFraction = 0.5f,
        )
        state.updateAnchorsForTest(containerHeightPx = 1000)
        state.snapTo(GeoVaultMapDrawerAnchor.Collapsed)

        val visible by state.visibleHeightPx
        assertEquals(200, visible)
    }

    @Test
    fun visibleHeightPx_halfExpanded_equalsFractionOfContainer() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 200,
            halfExpandedFraction = 0.55f,
        )
        state.updateAnchorsForTest(containerHeightPx = 1000)
        state.snapTo(GeoVaultMapDrawerAnchor.HalfExpanded)

        val visible by state.visibleHeightPx
        // container * fraction == 550
        assertEquals(550, visible)
    }

    @Test
    fun visibleHeightPx_expanded_equalsContainerHeight() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 200,
            halfExpandedFraction = 0.5f,
        )
        state.updateAnchorsForTest(containerHeightPx = 1000)
        state.snapTo(GeoVaultMapDrawerAnchor.Expanded)

        val visible by state.visibleHeightPx
        assertEquals(1000, visible)
    }

    @Test
    fun visibleHeightPx_tracksContainerResize() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 120,
            halfExpandedFraction = 0.5f,
        )
        state.updateAnchorsForTest(containerHeightPx = 800)
        state.snapTo(GeoVaultMapDrawerAnchor.HalfExpanded)

        val visible by state.visibleHeightPx
        assertEquals(400, visible)

        state.updateAnchorsForTest(containerHeightPx = 1200)
        // After resize, snapTo restores the same anchor at the new height.
        state.snapTo(GeoVaultMapDrawerAnchor.HalfExpanded)
        assertEquals(600, visible)
    }

    // ---------------------------------------------------------------------
    // currentAnchor / targetAnchor transitions
    // ---------------------------------------------------------------------

    @Test
    fun currentAnchor_startsAtInitialAnchor() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = 0.5f,
            initialAnchor = GeoVaultMapDrawerAnchor.HalfExpanded,
        )
        assertEquals(GeoVaultMapDrawerAnchor.HalfExpanded, state.currentAnchor)
        assertEquals(GeoVaultMapDrawerAnchor.HalfExpanded, state.targetAnchor)
    }

    @Test
    fun snapTo_updatesBothCurrentAndTargetAnchor() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = 0.5f,
        )
        state.updateAnchorsForTest(containerHeightPx = 1000)

        state.snapTo(GeoVaultMapDrawerAnchor.Expanded)

        assertEquals(GeoVaultMapDrawerAnchor.Expanded, state.currentAnchor)
        assertEquals(GeoVaultMapDrawerAnchor.Expanded, state.targetAnchor)
    }

    @Test
    fun animateTo_eventuallySettlesAtTarget() = runTest {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = 0.5f,
        )
        state.updateAnchorsForTest(containerHeightPx = 1000)

        state.animateTo(GeoVaultMapDrawerAnchor.Collapsed)

        assertEquals(GeoVaultMapDrawerAnchor.Collapsed, state.currentAnchor)
    }

    @Test
    fun containerHeightPx_reflectsLastUpdate() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 100,
            halfExpandedFraction = 0.5f,
        )
        assertEquals(0, state.containerHeightPx)

        state.updateAnchorsForTest(containerHeightPx = 640)
        assertEquals(640, state.containerHeightPx)

        state.updateAnchorsForTest(containerHeightPx = 960)
        assertEquals(960, state.containerHeightPx)
    }

    @Test
    fun peekHeightPx_andHalfExpandedFraction_areExposed() {
        val state = createGeoVaultMapDrawerStateForTest(
            peekHeightPx = 72,
            halfExpandedFraction = 0.42f,
        )
        assertEquals(72, state.peekHeightPx)
        assertTrue("fraction should be preserved", state.halfExpandedFraction == 0.42f)
    }
}

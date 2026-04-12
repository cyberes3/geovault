package com.geovault.tracker.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TrackerMapPaddingPolicyTest {

    @Test
    fun computeBoundsFitPaddingPx_usesConfiguredChipViewportReserve() {
        val policy = TrackerMapPaddingPolicy()
        val density = Density(2f)

        val boundsPx = policy.computeBoundsFitPaddingPx(density)

        // left = (16 + 8 + 88) * 2
        // top = (16 + 40) * 2
        // right = (16 + (16 + 44) + 12) * 2
        // bottom = 16 * 2
        assertArrayEquals(
            intArrayOf(224, 112, 176, 32),
            boundsPx,
        )
    }
}

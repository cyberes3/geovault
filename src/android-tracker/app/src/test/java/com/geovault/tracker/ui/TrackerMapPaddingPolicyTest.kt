package com.geovault.tracker.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TrackerMapPaddingPolicyTest {

    @Test
    fun computeBoundsFitPaddingPx_fallsBackToDefaultChipViewportReserveWhenUnmeasured() {
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

    @Test
    fun computeBoundsFitPaddingPx_usesMeasuredChipReserveWhenProvided() {
        val policy = TrackerMapPaddingPolicy()
        val density = Density(2f)

        // A taller chip (e.g. name + user label + status, all three lines) than the static
        // fallback guess must widen the top reserve accordingly rather than clipping content
        // behind it.
        val boundsPx = policy.computeBoundsFitPaddingPx(density, topLeftChipReserveDp = 72.dp)

        // top = (16 + 72) * 2
        assertArrayEquals(
            intArrayOf(224, 176, 176, 32),
            boundsPx,
        )
    }
}

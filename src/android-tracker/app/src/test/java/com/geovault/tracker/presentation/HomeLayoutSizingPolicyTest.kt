package com.geovault.tracker.presentation

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutSizingPolicyTest {

    @Test
    fun requiredNormalHeight_scalesPastUnscaledRawDpSum() {
        val density = Density(3f, 1f)
        val input = HomeLayoutSizingInput(
            density = density,
            previousMode = HomeLayoutMode.NORMAL,
            availableHeightPx = 100_000,
            occlusionPx = 0,
        )
        val required = HomeLayoutSizingPolicy.requiredNormalHeightPxForTesting(input)
        val naiveUnscaledPxSum = 420 + 180 + 24 + 32 + 16 + 12
        assertTrue(
            "expected density-scaled px ($required) > naive unscaled px sum ($naiveUnscaledPxSum)",
            required > naiveUnscaledPxSum,
        )
    }
}

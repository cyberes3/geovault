package com.geovault.common.maps.ui.scale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoVaultMapScaleBarCalculatorTest {

    @Test
    fun calculate_usesFeetBelowOneMile() {
        val measurement = requireNotNull(
            GeoVaultMapScaleBarCalculator.calculate(
                metersPerPixel = 1.0,
                maxWidthPx = 100,
            ),
        )

        assertEquals("300 ft", measurement.label)
        assertEquals(0.9144f, measurement.widthFraction, 0.0001f)
    }

    @Test
    fun calculate_usesMilesAboveOneMile() {
        val measurement = requireNotNull(
            GeoVaultMapScaleBarCalculator.calculate(
                metersPerPixel = 100.0,
                maxWidthPx = 100,
            ),
        )

        assertEquals("5 mi", measurement.label)
        assertEquals(0.8047f, measurement.widthFraction, 0.0001f)
    }

    @Test
    fun calculate_keepsFractionalFeetWhenZoomedIn() {
        val measurement = requireNotNull(
            GeoVaultMapScaleBarCalculator.calculate(
                metersPerPixel = 0.001,
                maxWidthPx = 100,
            ),
        )

        assertEquals("0.3 ft", measurement.label)
        assertEquals(0.9144f, measurement.widthFraction, 0.0001f)
    }

    @Test
    fun calculate_neverRoundsAboveAvailableDistance() {
        val measurement = requireNotNull(
            GeoVaultMapScaleBarCalculator.calculate(
                metersPerPixel = 0.00292608,
                maxWidthPx = 100,
            ),
        )

        assertEquals("0.5 ft", measurement.label)
        assertEquals(0.5208f, measurement.widthFraction, 0.0001f)
    }

    @Test
    fun calculate_rejectsInvalidInputs() {
        assertNull(GeoVaultMapScaleBarCalculator.calculate(Double.NaN, 100))
        assertNull(GeoVaultMapScaleBarCalculator.calculate(0.0, 100))
        assertNull(GeoVaultMapScaleBarCalculator.calculate(1.0, 0))
    }
}

package com.geovault.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceFormatTest {
    @Test
    fun formatTravel_stepsToMilesAndKilometers() {
        assertEquals("328 ft", DistanceFormat.formatTravel(100.0, MeasurementSystem.IMPERIAL).text)
        assertEquals("1.00 mi", DistanceFormat.formatTravel(DistanceFormat.METERS_PER_STATUTE_MILE, MeasurementSystem.IMPERIAL).text)
        assertEquals("500 m", DistanceFormat.formatTravel(500.0, MeasurementSystem.METRIC).text)
        assertEquals("1.5 km", DistanceFormat.formatTravel(1500.0, MeasurementSystem.METRIC).text)
    }

    @Test
    fun formatNavigation_staysInFeetUntilOverATenthMile() {
        assertEquals("33 ft", DistanceFormat.formatNavigation(10.0).text)
        assertEquals(
            "528 ft",
            DistanceFormat.formatNavigation(528.0 / DistanceFormat.FEET_PER_METER).text,
        )
        assertEquals("1.00 mi", DistanceFormat.formatNavigation(1609.344).text)
    }

    @Test
    fun formatAccuracy_usesSignedLength() {
        assertEquals("\u00B1100 m", DistanceFormat.formatAccuracy(100.0, MeasurementSystem.METRIC))
        assertEquals("\u00B1328 ft", DistanceFormat.formatAccuracy(100.0, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun measurementSystemFromLocale_usesImperialCountries() {
        assertEquals(MeasurementSystem.IMPERIAL, MeasurementSystem.fromLocale(java.util.Locale.US))
        assertEquals(MeasurementSystem.METRIC, MeasurementSystem.fromLocale(java.util.Locale.FRANCE))
    }

    @Test
    fun metersToDisplayMagnitude_imperialRoundsAndKeepsMinimumOne() {
        assertEquals("1", DistanceFormat.metersToDisplayMagnitude(0.31f, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun metersToDisplayMagnitude_metricZeroForNonPositive() {
        assertEquals("0", DistanceFormat.metersToDisplayMagnitude(0f, MeasurementSystem.METRIC))
    }

    @Test
    fun displayMagnitudeToMetersOrNull_convertsFeetToMeters() {
        val meters = DistanceFormat.displayMagnitudeToMetersOrNull("328.084", MeasurementSystem.IMPERIAL)
        assertEquals(100f, meters ?: 0f, 0.01f)
    }

    @Test
    fun displayMagnitudeToMetersOrNull_returnsNullForInvalidNumber() {
        assertNull(DistanceFormat.displayMagnitudeToMetersOrNull("abc", MeasurementSystem.METRIC))
    }

    @Test
    fun squareMetersToSquareFeet_usesFeetPerMeterSquared() {
        assertEquals(
            DistanceFormat.FEET_PER_METER * DistanceFormat.FEET_PER_METER,
            DistanceFormat.squareMetersToSquareFeet(1.0),
            0.0000001,
        )
    }

    @Test
    fun formatMilesOneDecimal_usesTenths() {
        assertEquals("1.0 mi", DistanceFormat.formatMilesOneDecimal(DistanceFormat.METERS_PER_STATUTE_MILE).text)
    }
}

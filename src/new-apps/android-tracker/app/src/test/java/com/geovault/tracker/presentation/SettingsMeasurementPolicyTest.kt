package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsMeasurementPolicyTest {

    @Test
    fun metersToDisplayText_imperialRoundsAndKeepsMinimumOne() {
        assertEquals("1", SettingsMeasurementPolicy.metersToDisplayText(meters = 0.31f, usesImperial = true))
    }

    @Test
    fun metersToDisplayText_metricZeroForNonPositive() {
        assertEquals("0", SettingsMeasurementPolicy.metersToDisplayText(meters = 0f, usesImperial = false))
    }

    @Test
    fun displayTextToMetersOrNull_convertsFeetToMeters() {
        val meters = SettingsMeasurementPolicy.displayTextToMetersOrNull(raw = "328.084", usesImperial = true)
        assertEquals(100f, meters ?: 0f, 0.01f)
    }

    @Test
    fun displayTextToMetersOrNull_returnsNullForInvalidNumber() {
        assertNull(SettingsMeasurementPolicy.displayTextToMetersOrNull(raw = "abc", usesImperial = false))
    }
}

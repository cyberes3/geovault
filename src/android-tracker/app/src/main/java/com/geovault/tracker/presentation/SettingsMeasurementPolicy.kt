package com.geovault.tracker.presentation

import kotlin.math.roundToInt

object SettingsMeasurementPolicy {
    private const val FEET_PER_METER = 3.28084f

    fun metersToDisplayText(meters: Float, usesImperial: Boolean): String {
        val converted = if (usesImperial) meters * FEET_PER_METER else meters
        if (converted <= 0f) return "0"
        return converted.roundToInt().coerceAtLeast(1).toString()
    }

    fun displayTextToMetersOrNull(raw: String, usesImperial: Boolean): Float? {
        val displayValue = raw.toFloatOrNull() ?: return null
        return if (usesImperial) {
            displayValue / FEET_PER_METER
        } else {
            displayValue
        }
    }
}

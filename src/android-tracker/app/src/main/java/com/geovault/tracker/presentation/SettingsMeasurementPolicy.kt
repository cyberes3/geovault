package com.geovault.tracker.presentation

import com.geovault.common.util.DistanceFormat
import com.geovault.common.util.MeasurementSystem

object SettingsMeasurementPolicy {
    fun metersToDisplayText(meters: Float, usesImperial: Boolean): String {
        return DistanceFormat.metersToDisplayMagnitude(meters, MeasurementSystem.fromFlag(usesImperial))
    }

    fun displayTextToMetersOrNull(raw: String, usesImperial: Boolean): Float? {
        return DistanceFormat.displayMagnitudeToMetersOrNull(raw, MeasurementSystem.fromFlag(usesImperial))
    }
}

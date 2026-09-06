package com.geovault.common.util

import java.util.Locale
import kotlin.math.roundToInt

enum class DistanceUnit(val symbolUs: String) {
    METER("m"),
    KILOMETER("km"),
    FOOT("ft"),
    MILE("mi"),
}

data class FormattedDistance(
    val valueText: String,
    val unit: DistanceUnit,
) {
    val text: String get() = "$valueText ${unit.symbolUs}"
}

/**
 * Shared meter/foot/mile conversion and display rules for Home, params, navigation,
 * and the map scale bar.
 */
object DistanceFormat {
    const val FEET_PER_METER = 3.280839895
    const val FEET_PER_STATUTE_MILE = 5280.0
    const val METERS_PER_KILOMETER = 1000.0
    const val METERS_PER_STATUTE_MILE = FEET_PER_STATUTE_MILE / FEET_PER_METER

    /** Navigation overlay stays in feet until strictly more than this many statute miles. */
    const val NAVIGATION_MILES_THRESHOLD = 0.1

    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METER

    fun feetToMeters(feet: Double): Double = feet / FEET_PER_METER

    fun metersToMiles(meters: Double): Double = metersToFeet(meters) / FEET_PER_STATUTE_MILE

    fun milesToMeters(miles: Double): Double = miles * METERS_PER_STATUTE_MILE

    /**
     * Session / travel distance: metric steps to km at 1000 m; imperial steps to miles at 1 mi.
     */
    fun formatTravel(meters: Double, system: MeasurementSystem): FormattedDistance {
        return if (system.usesImperial) {
            val feet = metersToFeet(meters)
            if (feet < FEET_PER_STATUTE_MILE) {
                FormattedDistance(feet.toInt().toString(), DistanceUnit.FOOT)
            } else {
                FormattedDistance(String.format(Locale.US, "%.2f", feet / FEET_PER_STATUTE_MILE), DistanceUnit.MILE)
            }
        } else if (meters < METERS_PER_KILOMETER) {
            FormattedDistance(meters.toInt().toString(), DistanceUnit.METER)
        } else {
            FormattedDistance(String.format(Locale.US, "%.1f", meters / METERS_PER_KILOMETER), DistanceUnit.KILOMETER)
        }
    }

    /**
     * Tracker `dist` param: metric steps to km above 1000 m (0 decimal km);
     * imperial steps to miles at 1 mi (1 decimal mi).
     */
    fun formatParamDistance(meters: Double, system: MeasurementSystem): FormattedDistance {
        return if (system.usesImperial) {
            val feet = metersToFeet(meters)
            if (feet >= FEET_PER_STATUTE_MILE) {
                FormattedDistance(String.format(Locale.US, "%.1f", feet / FEET_PER_STATUTE_MILE), DistanceUnit.MILE)
            } else {
                FormattedDistance(feet.toInt().toString(), DistanceUnit.FOOT)
            }
        } else if (meters > METERS_PER_KILOMETER) {
            FormattedDistance(String.format(Locale.US, "%.0f", meters / METERS_PER_KILOMETER), DistanceUnit.KILOMETER)
        } else {
            FormattedDistance(meters.toInt().toString(), DistanceUnit.METER)
        }
    }

    /** Navigation overlay: imperial survey-style (whole feet under 0.1 mi, then 2-decimal miles). */
    fun formatNavigation(meters: Double): FormattedDistance {
        val feet = metersToFeet(meters)
        val miles = feet / FEET_PER_STATUTE_MILE
        return if (miles > NAVIGATION_MILES_THRESHOLD) {
            FormattedDistance(String.format(Locale.US, "%.2f", miles), DistanceUnit.MILE)
        } else {
            FormattedDistance(String.format(Locale.US, "%.0f", feet), DistanceUnit.FOOT)
        }
    }

    /** Whole meters or feet for altitude / accuracy-style lengths. */
    fun formatLengthInteger(meters: Double, system: MeasurementSystem): FormattedDistance {
        val value = if (system.usesImperial) metersToFeet(meters).toInt() else meters.toInt()
        val unit = if (system.usesImperial) DistanceUnit.FOOT else DistanceUnit.METER
        return FormattedDistance(value.toString(), unit)
    }

    fun formatAccuracy(meters: Double, system: MeasurementSystem): String {
        val formatted = formatLengthInteger(meters, system)
        return "\u00B1${formatted.text}"
    }

    /**
     * Settings fields store meters but show a unitless magnitude in the active system.
     * Non-positive values render as `0`; any positive value is at least `1`.
     */
    fun metersToDisplayMagnitude(meters: Float, system: MeasurementSystem): String {
        val converted = if (system.usesImperial) metersToFeet(meters.toDouble()) else meters.toDouble()
        if (converted <= 0.0) return "0"
        return converted.roundToInt().coerceAtLeast(1).toString()
    }

    fun displayMagnitudeToMetersOrNull(raw: String, system: MeasurementSystem): Float? {
        val displayValue = raw.toFloatOrNull() ?: return null
        return if (system.usesImperial) {
            feetToMeters(displayValue.toDouble()).toFloat()
        } else {
            displayValue
        }
    }

    /** Scale-bar number: integers at or above 1, otherwise trimmed two-decimal US format. */
    fun formatScaleMagnitude(distance: Double): String {
        return if (distance >= 1.0) {
            distance.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", distance).trimEnd('0').trimEnd('.')
        }
    }
}

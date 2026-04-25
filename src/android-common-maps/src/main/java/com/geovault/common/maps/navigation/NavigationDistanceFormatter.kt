package com.geovault.common.maps.navigation

import java.util.Locale

/**
 * Formats the on-map label alongside a "navigate to" target.
 *
 * Output: name + newline + distance (`Locale.US`), or distance-only, or name-only. Distance is
 * whole feet under **0.1 statute mile**, then miles with two fractional digits (survey-style).
 */
object NavigationDistanceFormatter {

    private const val METERS_TO_FEET = 3.28084
    private const val FEET_PER_STATUTE_MILE = 5280.0
    /** Show miles when farther than this many statute miles (528 ft stays as feet). */
    private const val MILES_DISPLAY_THRESHOLD = 0.1

    fun format(title: String?, distanceMeters: Double?): String {
        val trimmedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val distanceLine = distanceMeters?.let { formatDistance(it) }
        return when {
            trimmedTitle != null && distanceLine != null -> "$trimmedTitle\n$distanceLine"
            trimmedTitle != null -> trimmedTitle
            distanceLine != null -> distanceLine
            else -> ""
        }
    }

    fun formatDistance(meters: Double): String {
        val feet = meters * METERS_TO_FEET
        val miles = feet / FEET_PER_STATUTE_MILE
        return if (miles > MILES_DISPLAY_THRESHOLD) {
            String.format(Locale.US, "%.2f mi", miles)
        } else {
            String.format(Locale.US, "%.0f ft", feet)
        }
    }
}

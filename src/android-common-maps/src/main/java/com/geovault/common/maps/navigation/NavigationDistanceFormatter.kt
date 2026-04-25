package com.geovault.common.maps.navigation

import java.util.Locale

/**
 * Formats the on-map label alongside a "navigate to" target.
 *
 * Output: name + newline + whole feet, `Locale.US`, or distance-only, or name-only, matching
 * the legacy survey map overlay.
 */
object NavigationDistanceFormatter {

    private const val METERS_TO_FEET = 3.28084

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
        return String.format(Locale.US, "%.0f ft", feet)
    }
}

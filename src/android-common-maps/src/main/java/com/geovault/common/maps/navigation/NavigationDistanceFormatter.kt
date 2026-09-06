package com.geovault.common.maps.navigation

import com.geovault.common.util.DistanceFormat

/**
 * Formats the on-map label alongside a "navigate to" target.
 *
 * Output: name + newline + distance (`Locale.US`), or distance-only, or name-only. Distance is
 * whole feet under **0.1 statute mile**, then miles with two fractional digits (survey-style).
 */
object NavigationDistanceFormatter {

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

    fun formatDistance(meters: Double): String = DistanceFormat.formatNavigation(meters).text
}

package com.geovault.common.maps.ui.scale

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

data class GeoVaultMapScaleBarMeasurement(
    val label: String,
    val widthFraction: Float,
)

object GeoVaultMapScaleBarCalculator {
    private const val FEET_PER_METER = 3.280839895
    private const val FEET_PER_MILE = 5280.0

    fun calculate(
        metersPerPixel: Double,
        maxWidthPx: Int,
    ): GeoVaultMapScaleBarMeasurement? {
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0 || maxWidthPx <= 0) return null

        val maxFeet = metersPerPixel * maxWidthPx * FEET_PER_METER
        val (maxDistance, unit) = if (maxFeet > FEET_PER_MILE) {
            maxFeet / FEET_PER_MILE to "mi"
        } else {
            maxFeet to "ft"
        }
        if (!maxDistance.isFinite() || maxDistance <= 0.0) return null

        val roundedDistance = roundDistance(maxDistance)
        val widthFraction = (roundedDistance / maxDistance)
            .toFloat()
            .coerceIn(0f, 1f)
        return GeoVaultMapScaleBarMeasurement(
            label = "${formatDistance(roundedDistance)} $unit",
            widthFraction = widthFraction,
        )
    }

    private fun roundDistance(distance: Double): Double {
        if (distance <= 0.0) return 0.0
        val pow10 = 10.0.pow(floor(log10(distance)))
        val candidate = distance / pow10
        val roundedCandidate = when {
            candidate >= 5.0 -> 5.0
            candidate >= 3.0 -> 3.0
            candidate >= 2.0 -> 2.0
            else -> 1.0
        }
        return roundedCandidate * pow10
    }

    private fun formatDistance(distance: Double): String {
        return if (distance >= 1.0) {
            distance.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", distance).trimEnd('0').trimEnd('.')
        }
    }
}

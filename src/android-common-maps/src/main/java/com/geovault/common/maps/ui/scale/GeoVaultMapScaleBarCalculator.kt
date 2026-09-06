package com.geovault.common.maps.ui.scale

import com.geovault.common.util.DistanceFormat
import com.geovault.common.util.DistanceUnit
import com.geovault.common.util.MeasurementSystem
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class GeoVaultMapScaleBarMeasurement(
    val label: String,
    val widthFraction: Float,
)

object GeoVaultMapScaleBarCalculator {
    fun calculate(
        metersPerPixel: Double,
        maxWidthPx: Int,
        system: MeasurementSystem = MeasurementSystem.IMPERIAL,
    ): GeoVaultMapScaleBarMeasurement? {
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0 || maxWidthPx <= 0) return null

        val maxMeters = metersPerPixel * maxWidthPx
        val (maxDistance, unit) = if (system.usesImperial) {
            val maxFeet = DistanceFormat.metersToFeet(maxMeters)
            if (maxFeet > DistanceFormat.FEET_PER_STATUTE_MILE) {
                maxFeet / DistanceFormat.FEET_PER_STATUTE_MILE to DistanceUnit.MILE
            } else {
                maxFeet to DistanceUnit.FOOT
            }
        } else if (maxMeters > DistanceFormat.METERS_PER_KILOMETER) {
            maxMeters / DistanceFormat.METERS_PER_KILOMETER to DistanceUnit.KILOMETER
        } else {
            maxMeters to DistanceUnit.METER
        }
        if (!maxDistance.isFinite() || maxDistance <= 0.0) return null

        val roundedDistance = roundDistance(maxDistance)
        val widthFraction = (roundedDistance / maxDistance)
            .toFloat()
            .coerceIn(0f, 1f)
        return GeoVaultMapScaleBarMeasurement(
            label = "${DistanceFormat.formatScaleMagnitude(roundedDistance)} ${unit.symbolUs}",
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
}

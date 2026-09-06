package com.geovault.tracker.params

import android.content.Context
import com.geovault.common.util.DistanceFormat
import com.geovault.common.util.DistanceUnit
import com.geovault.common.util.MeasurementSystem
import com.geovault.tracker.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PARAM_LABELS = mapOf(
    "lat" to "Latitude",
    "lon" to "Longitude",
    "timestamp" to "Timestamp",
    "sat" to "Satellites",
    "desc" to "Description",
    "alt" to "Altitude",
    "acc" to "Accuracy",
    "bearing" to "Bearing",
    "prov" to "Provider",
    "spd_kph" to "Speed",
    "starttimestamp" to "Start Timestamp",
    "batt" to "Battery",
    "ischarging" to "Charging",
    "ser" to "Serial",
    "dist" to "Distance",
)

private const val KMH_TO_MPH = 0.621371

class TrackerParamValueFormatter(private val context: Context) {

    private val measurementSystem: MeasurementSystem
        get() = MeasurementSystem.fromContext(context)

    fun labelForKey(key: String): String = PARAM_LABELS[key] ?: key

    fun formatDisplay(key: String, value: Any?): String {
        if (value == null) return ""
        return formatParamDisplay(key, value)
    }

    private fun formatParamDisplay(key: String, value: Any): String {
        val ctx = context
        when (key) {
            "alt", "acc" -> {
                val m = (value as? Number)?.toDouble() ?: return value.toString()
                val formatted = DistanceFormat.formatLengthInteger(m, measurementSystem)
                return "${formatted.valueText} ${unitLabel(ctx, formatted.unit)}"
            }

            "bearing" -> {
                val n = (value as? Number)?.toDouble() ?: return value.toString()
                return "%.0f°".format(Locale.US, n)
            }

            "prov" -> return value.toString().uppercase(Locale.getDefault())

            "spd_kph" -> {
                val kph = (value as? Number)?.toDouble() ?: return value.toString()
                return if (measurementSystem.usesImperial) {
                    val mph = (kph * KMH_TO_MPH).toInt()
                    "$mph ${ctx.getString(R.string.unit_mph)}"
                } else {
                    "${kph.toInt()} ${ctx.getString(R.string.unit_kmh)}"
                }
            }

            "starttimestamp" -> {
                val ms = when (value) {
                    is Number -> if (value.toLong() < 1e12) value.toLong() * 1000 else value.toLong()
                    else -> null
                }
                if (ms != null) {
                    val sdf = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())
                    return sdf.format(Date(ms))
                }
                return value.toString()
            }

            "batt" -> {
                val n = (value as? Number)?.toDouble() ?: return value.toString()
                return "%.0f%%".format(Locale.US, n)
            }

            "ischarging" -> return when (value) {
                true, "true", "1" -> ctx.getString(R.string.yes)
                else -> ctx.getString(R.string.no)
            }

            "dist" -> {
                val m = (value as? Number)?.toDouble() ?: return value.toString()
                val formatted = DistanceFormat.formatParamDistance(m, measurementSystem)
                return "${formatted.valueText} ${unitLabel(ctx, formatted.unit)}"
            }

            else -> return value.toString()
        }
    }

    private fun unitLabel(ctx: Context, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.METER -> ctx.getString(R.string.unit_m)
        DistanceUnit.KILOMETER -> ctx.getString(R.string.unit_km)
        DistanceUnit.FOOT -> ctx.getString(R.string.unit_ft)
        DistanceUnit.MILE -> ctx.getString(R.string.unit_mi)
    }
}

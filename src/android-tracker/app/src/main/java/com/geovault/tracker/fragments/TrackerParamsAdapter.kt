package com.geovault.tracker.fragments

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.geovault.common.UnitUtils
import com.geovault.tracker.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackerParamEntry(val key: String, val value: Any?)

class TrackerParamsAdapter(
    private val context: Context,
    private val entries: List<TrackerParamEntry>
) : RecyclerView.Adapter<TrackerParamsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_param_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (key, value) = entries[position]
        holder.label.text = PARAM_LABELS[key] ?: key
        holder.value.text = formatParamDisplay(context, key, value)
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(R.id.paramLabel)
        val value: TextView = itemView.findViewById(R.id.paramValue)
    }
}

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

private const val METERS_TO_FEET = 3.28084
private const val KMH_TO_MPH = 0.621371

private fun usesImperialUnits(context: Context): Boolean {
    return UnitUtils.usesImperialUnitsDefault(context)
}

private fun formatParamDisplay(context: Context, key: String, value: Any?): String {
    if (value == null) return ""
    val imperial = usesImperialUnits(context)
    when (key) {
        "alt", "acc" -> {
            val m = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val ft = (m * METERS_TO_FEET).toInt()
                "${ft} ${context.getString(R.string.unit_ft)}"
            } else {
                "${m.toInt()} ${context.getString(R.string.unit_m)}"
            }
        }

        "bearing" -> {
            val n = (value as? Number)?.toDouble() ?: return value.toString()
            return "%.0f°".format(Locale.US, n)
        }

        "prov" -> return value.toString().uppercase(Locale.getDefault())

        "spd_kph" -> {
            val kph = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val mph = (kph * KMH_TO_MPH).toInt()
                "$mph ${context.getString(R.string.unit_mph)}"
            } else {
                "${kph.toInt()} ${context.getString(R.string.unit_kmh)}"
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
            true, "true", "1" -> "Yes"
            else -> "No"
        }

        "dist" -> {
            val m = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val ft = m * METERS_TO_FEET
                if (ft >= 5280) {
                    "%.1f ${context.getString(R.string.unit_mi)}".format(Locale.US, ft / 5280)
                } else {
                    "${ft.toInt()} ${context.getString(R.string.unit_ft)}"
                }
            } else {
                when {
                    m > 1000 -> "%.0f ${context.getString(R.string.unit_km)}".format(Locale.US, m / 1000)
                    else -> "${m.toInt()} ${context.getString(R.string.unit_m)}"
                }
            }
        }

        else -> return value.toString()
    }
}

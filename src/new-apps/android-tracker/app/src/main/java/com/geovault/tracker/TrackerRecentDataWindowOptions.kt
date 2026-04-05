package com.geovault.tracker

import android.content.Context
import com.geovault.tracker.R

/** API values and human labels for the tracker recent-data window (legacy [RecentDataWindowOptions]). */
object TrackerRecentDataWindowOptions {
    const val VALUE_ALL = "all"
    private val values = listOf(
        VALUE_ALL,
        "1min",
        "1h",
        "1d",
        "1w",
        "1m",
        "session",
        "current_session",
    )

    fun labels(context: Context): List<String> =
        listOf(
            context.getString(R.string.trackers_recent_data_all),
            context.getString(R.string.trackers_recent_data_1min),
            context.getString(R.string.trackers_recent_data_1h),
            context.getString(R.string.trackers_recent_data_1d),
            context.getString(R.string.trackers_recent_data_1w),
            context.getString(R.string.trackers_recent_data_1m),
            context.getString(R.string.trackers_recent_data_session),
            context.getString(R.string.trackers_recent_data_current_session),
        )

    fun valueForIndex(index: Int): String = values.getOrElse(index) { VALUE_ALL }

    fun indexForValue(value: String?): Int {
        val normalized = value?.trim().orEmpty()
        return values.indexOf(normalized).takeIf { it >= 0 } ?: 0
    }

    fun resolveValueFromInput(context: Context, rawInput: String): String? =
        resolveValueFromInput(rawInput = rawInput, labels = labels(context))

    fun resolveValueFromInput(rawInput: String, labels: List<String>): String? {
        val normalized = rawInput.trim()
        if (normalized.isEmpty()) return VALUE_ALL
        values.firstOrNull { it == normalized }?.let { return it }
        val labelIndex = labels.indexOf(normalized)
        if (labelIndex >= 0) return valueForIndex(labelIndex)
        return null
    }
}

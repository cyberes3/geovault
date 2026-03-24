package com.geovault.tracker.fragments

import android.content.Context
import com.geovault.tracker.R

object RecentDataWindowOptions {
    private val values = listOf("", "1min", "1h", "1d", "1w", "1m", "session")

    fun labels(context: Context): List<String> {
        return listOf(
            context.getString(R.string.recent_data_all),
            context.getString(R.string.recent_data_1min),
            context.getString(R.string.recent_data_1h),
            context.getString(R.string.recent_data_1d),
            context.getString(R.string.recent_data_1w),
            context.getString(R.string.recent_data_1m),
            context.getString(R.string.recent_data_session)
        )
    }

    fun valueForIndex(index: Int): String {
        return values.getOrElse(index) { "" }
    }

    fun indexForValue(value: String?): Int {
        val normalized = value?.trim().orEmpty()
        return values.indexOf(normalized).takeIf { it >= 0 } ?: 0
    }

    fun resolveValueFromInput(context: Context, rawInput: String): String? {
        return resolveValueFromInput(rawInput = rawInput, labels = labels(context))
    }

    fun resolveValueFromInput(rawInput: String, labels: List<String>): String? {
        val normalized = rawInput.trim()
        if (normalized.isEmpty()) return ""
        val direct = values.firstOrNull { it == normalized }
        if (direct != null) return direct
        val labelIndex = labels.indexOf(normalized)
        if (labelIndex >= 0) return valueForIndex(labelIndex)
        return null
    }
}

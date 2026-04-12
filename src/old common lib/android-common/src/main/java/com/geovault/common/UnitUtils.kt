package com.geovault.common

import android.content.Context
import java.util.Locale

object UnitUtils {
    /**
     * Detects if the device defaults to imperial units (US system) for distance.
     * Uses API 34+ measurement system preference if available.
     */
    fun usesImperialUnitsDefault(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        val country = locale.country
        return country == "US" || country == "LR" || country == "MM"
    }
}

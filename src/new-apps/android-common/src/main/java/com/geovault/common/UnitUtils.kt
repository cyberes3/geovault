package com.geovault.common

import android.content.Context
import java.util.Locale

object UnitUtils {
    /**
     * Returns true when the device default measurement preference is imperial.
     */
    fun usesImperialUnitsDefault(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        val country = locale.country
        return country == "US" || country == "LR" || country == "MM"
    }
}

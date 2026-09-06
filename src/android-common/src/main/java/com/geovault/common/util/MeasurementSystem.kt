package com.geovault.common.util

import android.content.Context
import java.util.Locale

enum class MeasurementSystem {
    METRIC,
    IMPERIAL,
    ;

    val usesImperial: Boolean get() = this == IMPERIAL

    companion object {
        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

        fun fromLocale(locale: Locale): MeasurementSystem {
            return if (locale.country in IMPERIAL_COUNTRIES) IMPERIAL else METRIC
        }

        fun fromContext(context: Context): MeasurementSystem {
            val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
            return fromLocale(locale)
        }

        fun fromFlag(usesImperial: Boolean): MeasurementSystem {
            return if (usesImperial) IMPERIAL else METRIC
        }
    }
}

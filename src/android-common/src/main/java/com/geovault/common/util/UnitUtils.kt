package com.geovault.common.util

import android.content.Context

object UnitUtils {
    /**
     * Returns true when the device default measurement preference is imperial.
     */
    fun usesImperialUnitsDefault(context: Context): Boolean {
        return MeasurementSystem.fromContext(context).usesImperial
    }
}

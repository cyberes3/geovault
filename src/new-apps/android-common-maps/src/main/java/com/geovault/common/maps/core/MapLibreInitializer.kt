package com.geovault.common.maps.core

import android.content.Context
import org.maplibre.android.MapLibre

object MapLibreInitializer {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        initialized = true
    }
}

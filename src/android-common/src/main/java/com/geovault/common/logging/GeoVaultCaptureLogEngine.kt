package com.geovault.common.logging

import android.app.Application
import android.content.Context
import com.geovault.common.BuildConfig

internal object GeoVaultCaptureLogEngine {

    private val engine =
        BufferedLogEngine(
            isEnabled = { BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED },
            insertThreadName = "GeoVaultCaptureLog",
            exportThreadName = "GeoVaultCaptureExport",
            tag = "GeoVaultCaptureLogEngine",
            eventPrefix = "capture",
            createStore = { GeoVaultCaptureLogStore(it) },
            writeExport = { context, store, requestId ->
                GeoVaultCaptureLogDownloadsExport.export(context, store, requestId)
            },
        )

    fun init(application: Application) {
        engine.init(application)
    }

    fun enqueue(level: Int, tag: String, message: String, throwable: String?) {
        engine.enqueue(level, tag, message, throwable)
    }

    fun exportToDownloads(context: Context): Boolean = engine.exportToDownloads(context)
}

package com.geovault.common.logging

import android.app.Application
import android.content.Context
import com.geovault.common.BuildConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object GeoVaultCaptureLogEngine {

    @Volatile
    private var application: Application? = null

    private val executorLock = Any()
    private var executor: ExecutorService? = null

    /** Only touched from the executor thread. */
    private var store: GeoVaultCaptureLogStore? = null

    fun init(application: Application) {
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        val app = application.applicationContext as Application
        this.application = app
        synchronized(executorLock) {
            if (executor == null) {
                executor = newExecutor()
            }
        }
    }

    fun enqueue(level: Int, tag: String, message: String, throwable: String?) {
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        val app = application ?: return
        val ex =
            synchronized(executorLock) {
                executor ?: return
            }
        ex.execute {
            ensureStore(app).insertLog(level, tag, message, throwable)
        }
    }

    fun runExport(context: Context, onComplete: () -> Unit) {
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) {
            onComplete()
            return
        }
        val app = context.applicationContext as Application
        synchronized(executorLock) {
            if (application == null) {
                application = app
            }
            if (executor == null) {
                executor = newExecutor()
            }
        }
        val ex =
            synchronized(executorLock) {
                executor!!
            }
        val appCtx = context.applicationContext
        ex.execute {
            try {
                GeoVaultCaptureLogDownloadsExport.export(appCtx, ensureStore(app))
            } finally {
                onComplete()
            }
        }
    }

    private fun ensureStore(app: Application): GeoVaultCaptureLogStore {
        return store ?: GeoVaultCaptureLogStore(app).also { store = it }
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "GeoVaultCaptureLog").apply { isDaemon = true }
        }
}

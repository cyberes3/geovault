package com.geovault.common.logging

import android.app.Application
import android.content.Context
import android.util.Log
import com.geovault.common.BuildConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object GeoVaultPointRecordingLogEngine {

    @Volatile
    private var application: Application? = null

    private val executorLock = Any()
    private var executor: ExecutorService? = null
    private var exportExecutor: ExecutorService? = null
    private val exportRunning = AtomicBoolean(false)
    private val nextExportRequestId = AtomicLong(1L)

    /** Only touched from the executor thread. */
    private var store: GeoVaultPointRecordingLogStore? = null

    fun init(application: Application) {
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) return
        val app = application.applicationContext as Application
        this.application = app
        synchronized(executorLock) {
            if (executor == null) {
                executor = newExecutor()
            }
        }
    }

    fun enqueue(level: Int, tag: String, message: String) {
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) return
        val app = application ?: return
        val ex =
            synchronized(executorLock) {
                executor ?: return
            }
        ex.execute {
            ensureStore(app).insertLog(level, tag, message, throwable = null)
        }
    }

    fun scheduleExport(context: Context): Boolean {
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) {
            return false
        }
        val requestId = nextExportRequestId.getAndIncrement()
        if (!exportRunning.compareAndSet(false, true)) {
            Log.w(TAG, "point_recording_export_already_running requestId=$requestId")
            return false
        }
        val app = context.applicationContext as Application
        synchronized(executorLock) {
            if (application == null) {
                application = app
            }
            if (executor == null) {
                executor = newExecutor()
            }
            if (exportExecutor == null) {
                exportExecutor = newExportExecutor()
            }
        }
        val exportEx =
            synchronized(executorLock) {
                exportExecutor!!
            }
        val appCtx = context.applicationContext
        Log.i(TAG, "point_recording_export_queued requestId=$requestId")
        exportEx.execute {
            try {
                GeoVaultPointRecordingLogDownloadsExport.export(appCtx, ensureStore(app), requestId)
            } catch (t: Throwable) {
                Log.e(TAG, "point_recording_export_failed requestId=$requestId error=uncaught", t)
            } finally {
                exportRunning.set(false)
            }
        }
        return true
    }

    private fun ensureStore(app: Application): GeoVaultPointRecordingLogStore {
        return store ?: GeoVaultPointRecordingLogStore(app).also { store = it }
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "GeoVaultPointRecordingLog").apply { isDaemon = true }
        }

    private fun newExportExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "GeoVaultPointRecordingExport").apply { isDaemon = true }
        }

    private const val TAG = "GeoVaultPointRecordingLogEngine"
}

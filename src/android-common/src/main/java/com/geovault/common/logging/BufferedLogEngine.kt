package com.geovault.common.logging

import android.app.Application
import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared insert/export engine for cache-directory buffered log stores.
 * Insert and export run on separate threads; store construction is locked so both
 * executors share one [GeoVaultBufferedLogSqliteStore] instance.
 */
class BufferedLogEngine<S : GeoVaultBufferedLogSqliteStore>(
    private val isEnabled: () -> Boolean,
    private val insertThreadName: String,
    private val exportThreadName: String,
    private val tag: String,
    private val eventPrefix: String,
    private val createStore: (Application) -> S,
    private val writeExport: (Context, S, Long) -> Unit,
) {

    @Volatile
    private var application: Application? = null

    private val executorLock = Any()
    private val storeLock = Any()
    private var executor: ExecutorService? = null
    private var exportExecutor: ExecutorService? = null
    private val exportRunning = AtomicBoolean(false)
    private val nextExportRequestId = AtomicLong(1L)

    private var store: S? = null

    fun init(application: Application) {
        if (!isEnabled()) return
        val app = application.applicationContext as Application
        this.application = app
        synchronized(executorLock) {
            if (executor == null) {
                executor = newInsertExecutor()
            }
        }
    }

    fun enqueue(level: Int, tag: String, message: String, throwable: String? = null) {
        if (!isEnabled()) return
        val app = application ?: return
        val ex =
            synchronized(executorLock) {
                executor ?: return
            }
        ex.execute {
            ensureStore(app).insertLog(level, tag, message, throwable)
        }
    }

    fun exportToDownloads(context: Context): Boolean {
        if (!isEnabled()) {
            return false
        }
        val requestId = nextExportRequestId.getAndIncrement()
        if (!exportRunning.compareAndSet(false, true)) {
            Log.w(tag, "${eventPrefix}_export_already_running requestId=$requestId")
            return false
        }
        val app = context.applicationContext as Application
        val exportEx =
            synchronized(executorLock) {
                if (application == null) {
                    application = app
                }
                if (executor == null) {
                    executor = newInsertExecutor()
                }
                if (exportExecutor == null) {
                    exportExecutor = newExportExecutor()
                }
                exportExecutor!!
            }
        val appCtx = context.applicationContext
        Log.i(tag, "${eventPrefix}_export_queued requestId=$requestId")
        exportEx.execute {
            try {
                writeExport(appCtx, ensureStore(app), requestId)
            } catch (t: Throwable) {
                Log.e(tag, "${eventPrefix}_export_failed requestId=$requestId error=uncaught", t)
            } finally {
                exportRunning.set(false)
            }
        }
        return true
    }

    private fun ensureStore(app: Application): S {
        synchronized(storeLock) {
            val existing = store
            if (existing != null) {
                return existing
            }
            val created = createStore(app)
            store = created
            return created
        }
    }

    private fun newInsertExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, insertThreadName).apply { isDaemon = true }
        }

    private fun newExportExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, exportThreadName).apply { isDaemon = true }
        }
}

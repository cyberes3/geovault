package com.geovault.common.logging

import android.app.Application
import android.util.Log
import com.geovault.common.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Mirrors [android.util.Log] to logcat and, when [BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED] is
 * true (tracker `./build-android.sh debug|release --add-logging` or `-PGEOVAULT_ADD_LOGGING=true`), persists lines to a SQLite DB in app cache
 * (see project plan). Call [init] from [Application.onCreate].
 */
object GeoVaultCaptureLog {

    fun init(application: Application) {
        GeoVaultCaptureLogEngine.init(application)
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.VERBOSE, tag, msg, null)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable) {
        Log.v(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.VERBOSE, tag, msg, throwableString(tr))
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.DEBUG, tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable) {
        Log.d(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.DEBUG, tag, msg, throwableString(tr))
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.INFO, tag, msg, null)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable) {
        Log.i(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.INFO, tag, msg, throwableString(tr))
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.WARN, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.WARN, tag, msg, throwableString(tr))
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable) {
        Log.w(tag, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.WARN, tag, "", throwableString(tr))
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.ERROR, tag, msg, null)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.ERROR, tag, msg, throwableString(tr))
    }

    @JvmStatic
    fun wtf(tag: String, msg: String) {
        Log.wtf(tag, msg)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.ASSERT, tag, msg, null)
    }

    @JvmStatic
    fun wtf(tag: String, tr: Throwable) {
        Log.wtf(tag, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.ASSERT, tag, "", throwableString(tr))
    }

    @JvmStatic
    fun wtf(tag: String, msg: String, tr: Throwable) {
        Log.wtf(tag, msg, tr)
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) return
        GeoVaultCaptureLogEngine.enqueue(Log.ASSERT, tag, msg, throwableString(tr))
    }

    private fun throwableString(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}

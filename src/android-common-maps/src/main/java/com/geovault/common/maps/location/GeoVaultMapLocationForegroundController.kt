package com.geovault.common.maps.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts and stops [GeoVaultMapLocationForegroundService]. Kept separate from the Service so
 * [GeoVaultMapGpsLocationEngine] can be unit-tested with a fake controller.
 */
interface GeoVaultMapLocationForegroundController {
    fun ensureStarted(context: Context)
    fun ensureStopped(context: Context)
}

object AndroidGeoVaultMapLocationForegroundController : GeoVaultMapLocationForegroundController {
    override fun ensureStarted(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, GeoVaultMapLocationForegroundService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
    }

    override fun ensureStopped(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, GeoVaultMapLocationForegroundService::class.java))
    }
}

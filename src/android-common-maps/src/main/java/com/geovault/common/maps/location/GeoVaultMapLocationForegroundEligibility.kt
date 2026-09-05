package com.geovault.common.maps.location

import android.app.ActivityManager

/**
 * Whether this process may start a location foreground service right now.
 *
 * Starting [android.content.Context.startForegroundService] from the background is rejected by
 * the platform for location/camera/microphone types.
 */
object GeoVaultMapLocationForegroundEligibility {
    fun canStart(importance: Int): Boolean {
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    fun canStartFromCurrentProcess(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return canStart(processInfo.importance)
    }
}

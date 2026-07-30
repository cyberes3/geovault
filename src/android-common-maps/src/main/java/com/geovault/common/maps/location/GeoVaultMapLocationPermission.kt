package com.geovault.common.maps.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Fine + coarse location permission contract for map UIs in this Gradle module only. */
object GeoVaultMapLocationPermission {

    val FINE_AND_COARSE: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}

fun Context.geoVaultMapHasFineOrCoarseLocation(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}

/** Required so the map GPS location foreground-service notification is visible. */
fun Context.geoVaultMapHasPostNotifications(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

package com.geovault.tracker.location

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.geovault.common.logging.GeoVaultCaptureLog

object TrackingPermissionGate {
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasAnyLocationPermission(context: Context): Boolean {
        return hasLocationPermission(context) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasRequiredPermissionsForTracking(context: Context): Boolean {
        return hasLocationPermission(context) &&
            hasBackgroundLocationPermission(context) &&
            hasNotificationPermission(context)
    }

    fun canStartTracking(context: Context): Boolean {
        return TrackingLocationAvailabilityPolicy.canStartTracking(
            TrackingLocationAvailabilityInput(
                hasFineLocationPermission = hasLocationPermission(context),
                hasBackgroundLocationPermission = hasBackgroundLocationPermission(context),
                hasNotificationPermission = hasNotificationPermission(context),
                locationServicesEnabled = isLocationServicesEnabled(context),
            )
        )
    }

    fun hasActivityRecognitionPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * On GrapheneOS [OTHER_SENSORS_PERMISSION] is a real runtime permission that the user must
     * explicitly allow (see [isOtherSensorsPermissionKnownToOs]). On stock/vendor ROMs it is not
     * defined at all, so it can never be granted; in that case sensors access is treated as
     * implicitly available instead of gating on an impossible permission.
     */
    fun hasOtherSensorsPermission(context: Context): Boolean {
        if (!isOtherSensorsPermissionKnownToOs(context)) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, OTHER_SENSORS_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * [OTHER_SENSORS_PERMISSION] is a GrapheneOS-specific "special runtime permission" that does
     * not exist on stock/vendor AOSP. On those ROMs [PackageManager] has no record of it at all
     * (no package, including the OS itself, declares it), so [ContextCompat.checkSelfPermission]
     * always reports it denied and there is no system UI that can ever grant it — the user would
     * be stuck behind a permission gate they cannot satisfy. Detect that case by asking
     * [PackageManager] whether it knows about the permission; if it doesn't, callers should treat
     * sensors access as available rather than gating on it forever. Result is cached since it
     * cannot change during the process lifetime.
     */
    private fun isOtherSensorsPermissionKnownToOs(context: Context): Boolean {
        cachedOtherSensorsPermissionKnown?.let { return it }
        val known = runCatching {
            context.packageManager.getPermissionInfo(OTHER_SENSORS_PERMISSION, 0)
            true
        }.getOrDefault(false)
        cachedOtherSensorsPermissionKnown = known
        GeoVaultCaptureLog.i(TAG, "other_sensors_permission_known known=$known")
        return known
    }

    fun hasBatteryOptimizationExemption(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasExactAlarmPermission(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    fun isLocationServicesEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(locationManager) }.getOrDefault(false)
    }

    private const val OTHER_SENSORS_PERMISSION = "android.permission.OTHER_SENSORS"
    private const val TAG = "TrackingPermissionGate"

    @Volatile
    private var cachedOtherSensorsPermissionKnown: Boolean? = null
}

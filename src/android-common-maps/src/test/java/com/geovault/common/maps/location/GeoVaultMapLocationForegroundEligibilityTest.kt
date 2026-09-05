package com.geovault.common.maps.location

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMapLocationForegroundEligibilityTest {
    @Test
    fun canStart_whenProcessIsVisible() {
        assertTrue(
            GeoVaultMapLocationForegroundEligibility.canStart(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            )
        )
        assertTrue(
            GeoVaultMapLocationForegroundEligibility.canStart(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            )
        )
    }

    @Test
    fun cannotStart_whenProcessIsBackground() {
        assertFalse(
            GeoVaultMapLocationForegroundEligibility.canStart(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            )
        )
        assertFalse(
            GeoVaultMapLocationForegroundEligibility.canStart(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
            )
        )
    }
}

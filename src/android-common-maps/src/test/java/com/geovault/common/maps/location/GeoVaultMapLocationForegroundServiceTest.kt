package com.geovault.common.maps.location

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultMapLocationForegroundServiceTest {
    @Test
    fun startMode_isNotSticky() {
        assertEquals(Service.START_NOT_STICKY, GeoVaultMapLocationForegroundService.START_MODE)
    }
}

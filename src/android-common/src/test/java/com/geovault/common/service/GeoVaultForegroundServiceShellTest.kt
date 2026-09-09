package com.geovault.common.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GeoVaultForegroundServiceShellTest {

    class TestService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    @Test
    fun promote_returnsPromotedOnSuccess() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        val shell = GeoVaultForegroundServiceShell(
            service = service,
            notificationId = 101,
            foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            tag = "ShellTest",
        )
        val notification = Notification()
        val result = shell.promote(notification)
        assertTrue(result is GeoVaultForegroundServiceShell.PromotionResult.Promoted)
        controller.destroy()
    }
}

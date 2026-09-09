package com.geovault.common.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GeoVaultRuntimeWatchdogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun schedule_thenCancel_clearsAlarm() {
        val watchdog = GeoVaultRuntimeWatchdog(
            context = context,
            config = GeoVaultRuntimeWatchdog.Config(
                requestCode = 20101,
                tag = "WatchdogTest",
            ),
            tickIntentFactory = {
                Intent("com.geovault.test.WATCHDOG").apply { setPackage(context.packageName) }
            },
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        watchdog.schedule(1_000L)
        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isNotEmpty())
        watchdog.cancel()
        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }
}

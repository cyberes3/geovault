package com.geovault.common.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GeoVaultForegroundStartGateTest {

    private lateinit var context: Context
    private var nowMs = 10_000L
    private var startCount = 0
    private var failNextStart = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nowMs = 10_000L
        startCount = 0
        failNextStart = false
        context.getSharedPreferences("gate_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun dispatchStart_successResetsBackoff() {
        val gate = gate()
        val decision = gate.dispatchStart("ui")
        assertTrue(decision.allowed)
        assertEquals("start_dispatched:ui", decision.reason)
        assertEquals(1, startCount)
    }

    @Test
    fun dispatchStart_deniedSchedulesRetryAndCancelClearsIt() {
        failNextStart = true
        val gate = gate()
        val decision = gate.dispatchStart("ui")
        assertFalse(decision.allowed)
        assertTrue(decision.reason.startsWith("start_failed_"))
        assertTrue(decision.retryInMs > 0L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isNotEmpty())
        gate.cancelRetry()
        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun dispatchStart_respectsMinGap() {
        val gate = gate()
        assertTrue(gate.dispatchStart("first").allowed)
        val blocked = gate.dispatchStart("second")
        assertFalse(blocked.allowed)
        assertEquals("min_gap", blocked.reason)
        assertEquals(1, startCount)
    }

    private fun gate(): GeoVaultForegroundStartGate {
        return GeoVaultForegroundStartGate(
            context = context,
            config = GeoVaultForegroundStartGate.Config(
                prefsName = "gate_test",
                retryRequestCode = 99,
                tag = "GateTest",
            ),
            startIntentFactory = {
                Intent(context, android.app.Service::class.java).apply {
                    action = "TEST_START"
                    setPackage(context.packageName)
                }
            },
            starter = GeoVaultForegroundStartGate.ForegroundServiceStarter { _, _ ->
                startCount += 1
                if (failNextStart) {
                    failNextStart = false
                    throw ForegroundServiceStartNotAllowedException("denied")
                }
            },
            elapsedClock = { nowMs },
        )
    }
}

package com.geovault.tracker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceDeliveryHelperTest {

    @Test
    fun deliver_startServiceSuccess_doesNotEscalateToForegroundService() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val starter = RecordingStarter()

        val result = TrackingServiceDeliveryHelper.deliver(
            context = context,
            intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingServiceIntents.ACTION_LOCATION_UPDATE
            },
            source = TrackingServiceDeliverySource.FusedLocationUpdate,
            starter = starter,
            runtimeSnapshot = TrackingRuntimeSnapshot(isRunning = true),
        )

        assertTrue(result is TrackingServiceDeliveryResult.Started)
        assertEquals(1, starter.started.size)
        assertEquals(0, starter.foregroundStarted.size)
        assertFalse(starter.started.single().requiresForegroundServiceStart())
    }

    @Test
    fun deliver_locationUpdateStartServiceRejectedWhileTracking_escalatesWithForegroundDeadlineExtra() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val starter = RecordingStarter(rejectStartService = true)

        val result = TrackingServiceDeliveryHelper.deliver(
            context = context,
            intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingServiceIntents.ACTION_LOCATION_UPDATE
            },
            source = TrackingServiceDeliverySource.FusedLocationUpdate,
            starter = starter,
            runtimeSnapshot = TrackingRuntimeSnapshot(isRunning = true),
        )

        assertTrue(result is TrackingServiceDeliveryResult.Started)
        assertEquals(1, starter.foregroundStarted.size)
        val escalated = starter.foregroundStarted.single()
        assertTrue(escalated.requiresForegroundServiceStart())
        assertEquals(
            TrackingServiceDeliverySource.FusedLocationUpdate.logName,
            escalated.getStringExtra(TrackingServiceIntents.EXTRA_BACKGROUND_WAKEUP_SOURCE)
        )
    }

    @Test
    fun deliver_startServiceRejectedWhileInactive_failsWithoutForegroundEscalation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val starter = RecordingStarter(rejectStartService = true)

        val result = TrackingServiceDeliveryHelper.deliver(
            context = context,
            intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingServiceIntents.ACTION_LOCATION_UPDATE
            },
            source = TrackingServiceDeliverySource.FusedLocationUpdate,
            starter = starter,
            runtimeSnapshot = TrackingRuntimeSnapshot(isRunning = false),
        )

        assertTrue(result is TrackingServiceDeliveryResult.Failed)
        assertEquals(0, starter.foregroundStarted.size)
    }

    @Test
    fun requiresForegroundPromotion_escalatedWakeupPathsPromote() {
        assertTrue(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.StartupCommandPath.LocationUpdate,
                foregroundStartRequired = true,
            )
        )
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.StartupCommandPath.LocationUpdate,
                foregroundStartRequired = false,
            )
        )
    }

    private class RecordingStarter(
        private val rejectStartService: Boolean = false,
    ) : TrackingServiceStarter {
        val started = mutableListOf<Intent>()
        val foregroundStarted = mutableListOf<Intent>()

        override fun startService(context: Context, intent: Intent) {
            if (rejectStartService) {
                throw IllegalStateException("background start rejected")
            }
            started += Intent(intent)
        }

        override fun startForegroundService(context: Context, intent: Intent) {
            foregroundStarted += Intent(intent)
        }
    }

    private fun Intent.requiresForegroundServiceStart(): Boolean {
        return getBooleanExtra(TrackingServiceIntents.EXTRA_FOREGROUND_SERVICE_START_REQUIRED, false)
    }
}

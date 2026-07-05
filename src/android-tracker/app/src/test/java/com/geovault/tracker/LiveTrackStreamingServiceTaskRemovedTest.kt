package com.geovault.tracker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.policy.RemoteStreamIngressPolicy
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.OwnerLease
import com.geovault.tracker.streaming.StreamingOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveTrackStreamingServiceTaskRemovedTest {

    @After
    fun tearDown() {
        RemoteStreamIngressPolicy.resetForTests()
    }

    @Test
    fun onTaskRemoved_clearsRemoteStreamAdmissionState() {
        // STALE-ADMISSION-STATE: stopStreamingSession() (invoked via onTaskRemoved) must clear
        // RemoteStreamIngressPolicy's per-track ordering bookkeeping, since a REUSE/HOT_UPDATE
        // decision on a later session never calls startSubscriptionSession() itself.
        val now = 1_700_000_000_000L
        RemoteStreamIngressPolicy.startSubscriptionSession(listOf("t1"))
        assertNotNull(
            RemoteStreamIngressPolicy.process(
                event = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "t1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = now,
                ),
                nowMs = now,
            )
        )

        val controller = Robolectric.buildService(LiveTrackStreamingService::class.java).create()
        controller.get().onTaskRemoved(null)
        controller.destroy()

        // Without the stop-time reset, this older timestamp would be rejected as out-of-order
        // against the still-live per-track anchor recorded before the stop.
        val acceptedAfterStop = RemoteStreamIngressPolicy.process(
            event = TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = "t1",
                lon = 10.0,
                lat = 20.0,
                timestampMs = now - 5_000L,
            ),
            nowMs = now,
        )
        assertNotNull(acceptedAfterStop)
    }

    @Test
    fun onTaskRemoved_clearsPersistedTargetsAndStopsRuntimeState() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("t1"))
            .putString("tracker_name", "Tracker 1")
            .commit()
        val repository = TrackerAppServices.from(context.applicationContext as Application)
            .liveStreamSubscriptionRepository()
        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("t1"), displayName = "Tracker 1"))
        val controller = Robolectric.buildService(LiveTrackStreamingService::class.java).create()
        val service = controller.get()

        service.onTaskRemoved(null)

        val snapshot = repository.state.value
        assertTrue(prefs.getStringSet("tracker_ids", null).orEmpty().isEmpty())
        assertFalse(prefs.contains("tracker_name"))
        assertTrue(snapshot.leases.isEmpty())
        assertFalse(snapshot.wantsSubscription)
        assertEquals(ConnectionPhase.IDLE, snapshot.connection)
        assertTrue(snapshot.activeTargets.isEmpty())
        controller.destroy()
    }
}

package com.geovault.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.StreamingHealth
import com.geovault.tracker.services.StreamingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveTrackStreamingServiceTaskRemovedTest {

    @Test
    fun onTaskRemoved_clearsPersistedTargetsAndStopsRuntimeState() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("t1"))
            .putString("tracker_name", "Tracker 1")
            .commit()
        LiveStreamRuntimeStateStore.update {
            LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("t1"),
            )
        }
        val controller = Robolectric.buildService(LiveTrackStreamingService::class.java).create()
        val service = controller.get()

        service.onTaskRemoved(null)

        val snapshot = LiveStreamRuntimeStateStore.state.value
        assertTrue(prefs.getStringSet("tracker_ids", null).orEmpty().isEmpty())
        assertFalse(prefs.contains("tracker_name"))
        assertEquals(StreamingIntent.Idle, snapshot.intent)
        assertEquals(StreamingHealth.Stopped, snapshot.health)
        assertTrue(snapshot.activeTrackerIds.isEmpty())
        controller.destroy()
    }
}

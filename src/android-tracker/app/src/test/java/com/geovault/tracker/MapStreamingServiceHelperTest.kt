package com.geovault.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MapStreamingServiceHelperTest {

    @Test
    fun clearPersistedStreamingTargets_removesSavedIds() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("t1"))
            .putString("tracker_name", "N")
            .apply()

        MapStreamingServiceHelper.clearPersistedStreamingTargets(context)

        assertTrue(prefs.getStringSet("tracker_ids", null).orEmpty().isEmpty())
        assertFalse(prefs.contains("tracker_name"))
    }

    @Test
    fun stopStreaming_clearsSavedIdsBeforeDispatchingStopCommand() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("t1"))
            .putString("tracker_name", "N")
            .apply()

        val result = MapStreamingServiceHelper.stopStreaming(context)

        assertTrue(result is MapStreamingStopResult.Stopped)
        assertTrue(prefs.getStringSet("tracker_ids", null).orEmpty().isEmpty())
        assertFalse(prefs.contains("tracker_name"))
    }
}

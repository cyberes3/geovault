package com.geovault.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun stopStreaming_returnsStoppedAndLeavesPrefsForServiceToClear() {
        // STOP-PREFS-ORDER: the helper hands the stop intent to the service and lets the service
        // clear prefs from inside ACTION_STOP. Verifying the helper no longer pre-clears prefs
        // protects us from regressions where a failed stop intent would otherwise leave runtime
        // state and prefs out of sync.
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("t1"))
            .putString("tracker_name", "N")
            .apply()

        val result = MapStreamingServiceHelper.stopStreaming(context)

        assertTrue(result is MapStreamingStopResult.Stopped)
        assertEquals(setOf("t1"), prefs.getStringSet("tracker_ids", null))
        assertEquals("N", prefs.getString("tracker_name", null))
    }

    @Test
    fun persistedTargets_returnsSavedIdsVerbatim() {
        // STREAMING TRUST: the helper persists whatever the upstream pipeline asked it to persist.
        // It does NOT re-apply selected / locally-recorded exclusion; that is the projector's
        // job at the moment of dispatch. This test pins the pass-through contract.
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("self", "remote"))
            .putString("tracker_name", "Group")
            .apply()

        val (ids, name) = MapStreamingServiceHelper.persistedTargets(context)

        assertEquals(setOf("self", "remote"), ids)
        assertEquals("Group", name)
        assertEquals(setOf("self", "remote"), prefs.getStringSet("tracker_ids", null))
    }

    @Test
    fun persistedTargets_normalizesBlankIdsAndRewritesPrefs() {
        // Whitespace-only ids are dropped because they cannot identify a tracker; the persisted
        // prefs are rewritten to the normalized form so we don't keep accumulating blanks.
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("live_track_streaming_targets", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("tracker_ids", setOf("remote", " "))
            .putString("tracker_name", "Group")
            .apply()

        val (ids, _) = MapStreamingServiceHelper.persistedTargets(context)

        assertEquals(setOf("remote"), ids)
        assertEquals(setOf("remote"), prefs.getStringSet("tracker_ids", null))
    }
}

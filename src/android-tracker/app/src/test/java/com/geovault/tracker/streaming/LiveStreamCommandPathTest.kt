package com.geovault.tracker.streaming

import android.app.Application
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.LiveTrackStreamingService
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
class LiveStreamCommandPathTest {

    @Test
    fun commandFromAction_mapsApplyStopReshow() {
        val controller = Robolectric.buildService(LiveTrackStreamingService::class.java)
        val service = controller.create().get()
        val runtime = LiveStreamRuntime.create(service)
        assertEquals(LiveStreamCommand.Apply, runtime.commandFromAction(LiveTrackStreamingService.ACTION_START))
        assertEquals(LiveStreamCommand.Stop, runtime.commandFromAction(LiveTrackStreamingService.ACTION_STOP))
        assertEquals(LiveStreamCommand.Reshow, runtime.commandFromAction(LiveTrackStreamingService.ACTION_RESHOW_FOREGROUND))
        controller.destroy()
    }

    @Test
    fun persistPort_commitThenClear() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val persist = SharedPrefsLiveStreamPersistPort(context)
        persist.commit(setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Trail")
        val (ids, name) = persist.read()
        assertEquals(setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), ids)
        assertEquals("Trail", name)
        persist.clear()
        val cleared = persist.read()
        assertTrue(cleared.first.isEmpty())
        assertEquals(null, cleared.second)
    }

    @Test
    fun persistPort_restorePath_roundTripsCommittedTargets() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val persist = SharedPrefsLiveStreamPersistPort(context)
        persist.clear()
        persist.commit(setOf("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "Restore")
        val restored = persist.read()
        assertEquals(setOf("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), restored.first)
        assertEquals("Restore", restored.second)
        persist.clear()
    }

    @Test
    fun onStartCommand_nullIntentEmptyPersist_returnsNotSticky() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        SharedPrefsLiveStreamPersistPort(context).clear()
        val controller = Robolectric.buildService(LiveTrackStreamingService::class.java)
        val service = controller.create().get()
        val result = service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun hostPort_stopClearsPersist() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val persist = SharedPrefsLiveStreamPersistPort(context)
        persist.commit(setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Trail")
        val host = DefaultLiveStreamHostPort(context, persist)
        val result = host.stop(context)
        assertTrue(result is LiveStreamStopResult.Stopped)
        assertTrue(persist.read().first.isEmpty())
        host.cancelRetry()
        assertFalse(persist.read().first.isNotEmpty())
    }
}

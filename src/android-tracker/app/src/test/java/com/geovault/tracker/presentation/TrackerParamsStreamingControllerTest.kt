package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerParamsStreamingControllerTest {

    @Test
    fun onScreenStarted_remoteTracker_replacesParamsLease() {
        val sink = FakeLeaseSink()
        val controller = controller(sink)

        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "local",
            trackingRunning = true,
            streamSnapshot = LiveStreamRuntimeSnapshot(),
        )

        assertEquals(setOf("remote"), sink.lastRequest?.trackerIds)
        assertEquals("Remote", sink.lastRequest?.trackerName)
        assertEquals("local", sink.lastRequest?.locallyRecordedTrackerId)
    }

    @Test
    fun onScreenStopped_startedFromIdle_clearsParamsLease() {
        val sink = FakeLeaseSink()
        val controller = controller(sink)
        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "",
            trackingRunning = false,
            streamSnapshot = LiveStreamRuntimeSnapshot(),
        )

        controller.onScreenStopped()

        assertEquals(null, sink.lastRequest)
    }

    @Test
    fun onScreenStarted_sameSessionStreamChanged_reappliesWithResetGate() {
        val streamState = MutableStateFlow(LiveStreamRuntimeSnapshot())
        val sink = FakeLeaseSink()
        val controller = controller(sink, streamState)
        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "",
            trackingRunning = false,
            streamSnapshot = streamState.value,
        )
        streamState.value = LiveStreamRuntimeSnapshot(
            isRunning = true,
            lifecycleState = TrackingLifecycleState.RUNNING,
            activeTrackerIds = setOf("remote"),
        )

        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "",
            trackingRunning = false,
            streamSnapshot = streamState.value,
        )

        assertEquals(1, sink.resetApplyGateCount)
        assertEquals(setOf("remote"), sink.lastRequest?.trackerIds)
    }

    private fun controller(
        sink: FakeLeaseSink,
        streamState: MutableStateFlow<LiveStreamRuntimeSnapshot> = MutableStateFlow(LiveStreamRuntimeSnapshot()),
    ): TrackerParamsStreamingController {
        val context: Context = ApplicationProvider.getApplicationContext()
        return TrackerParamsStreamingController(
            appContext = context,
            streamState = streamState,
            leaseSink = sink,
        )
    }

    private class FakeLeaseSink : TrackerParamsStreamingLeaseSink {
        var resetApplyGateCount: Int = 0
        var lastRequest: LiveTrackStreamingTargetRequest? = null

        override fun resetApplyGate() {
            resetApplyGateCount += 1
        }

        override fun replaceParamsRequest(context: Context, request: LiveTrackStreamingTargetRequest?) {
            lastRequest = request
        }
    }
}

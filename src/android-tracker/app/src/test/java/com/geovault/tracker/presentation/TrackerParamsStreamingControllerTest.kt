package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.streaming.LiveStreamServicePort
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.StreamingOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerParamsStreamingControllerTest {

    @Test
    fun onScreenStarted_remoteTracker_setsParamsLease() = runTest {
        val (controller, repository) = controller(this)

        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "local",
            trackingRunning = true,
        )
        advanceUntilIdle()

        val lease = repository.state.value.leases[StreamingOwner.PARAMS]
        assertEquals(setOf("remote"), lease?.trackerIds)
        assertEquals("Remote", lease?.displayName)
        assertEquals("local", lease?.locallyRecordedTrackerId)
    }

    @Test
    fun onScreenStarted_notLocallyRecording_leavesLocallyRecordedTrackerIdNull() = runTest {
        val (controller, repository) = controller(this)

        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "local",
            trackingRunning = false,
        )
        advanceUntilIdle()

        val lease = repository.state.value.leases[StreamingOwner.PARAMS]
        assertEquals(setOf("remote"), lease?.trackerIds)
        assertNull(lease?.locallyRecordedTrackerId)
    }

    @Test
    fun onScreenStarted_viewingOwnSelectedTracker_doesNotCreateParamsLease() = runTest {
        val (controller, repository) = controller(this)

        controller.onScreenStarted(
            trackerId = "selected",
            trackerName = "Selected",
            selectedTrackerId = "selected",
            trackingRunning = false,
        )
        advanceUntilIdle()

        assertNull(repository.state.value.leases[StreamingOwner.PARAMS])
    }

    @Test
    fun onScreenStarted_blankTrackerId_doesNotCreateParamsLease() = runTest {
        val (controller, repository) = controller(this)

        controller.onScreenStarted(
            trackerId = "   ",
            trackerName = "Ignored",
            selectedTrackerId = "local",
            trackingRunning = false,
        )
        advanceUntilIdle()

        assertNull(repository.state.value.leases[StreamingOwner.PARAMS])
    }

    @Test
    fun onScreenStopped_afterStarted_clearsParamsLease() = runTest {
        val (controller, repository) = controller(this)
        controller.onScreenStarted(
            trackerId = "remote",
            trackerName = "Remote",
            selectedTrackerId = "",
            trackingRunning = false,
        )
        advanceUntilIdle()

        controller.onScreenStopped()
        advanceUntilIdle()

        assertNull(repository.state.value.leases[StreamingOwner.PARAMS])
    }

    @Test
    fun onScreenStopped_withoutStarting_isANoOp() = runTest {
        val (controller, repository) = controller(this)

        controller.onScreenStopped()
        advanceUntilIdle()

        assertNull(repository.state.value.leases[StreamingOwner.PARAMS])
    }

    @Test
    fun onScreenStarted_switchingTracker_replacesParamsLease() = runTest {
        val (controller, repository) = controller(this)
        controller.onScreenStarted(
            trackerId = "remoteA",
            trackerName = "Remote A",
            selectedTrackerId = "",
            trackingRunning = false,
        )
        advanceUntilIdle()

        controller.onScreenStarted(
            trackerId = "remoteB",
            trackerName = "Remote B",
            selectedTrackerId = "",
            trackingRunning = false,
        )
        advanceUntilIdle()

        val lease = repository.state.value.leases[StreamingOwner.PARAMS]
        assertEquals(setOf("remoteB"), lease?.trackerIds)
        assertEquals("Remote B", lease?.displayName)
    }

    private fun controller(
        testScope: kotlinx.coroutines.test.TestScope,
    ): Pair<TrackerParamsStreamingController, LiveStreamSubscriptionRepository> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repository = LiveStreamSubscriptionRepository(
            appContext = context,
            servicePort = NoOpLiveStreamServicePort,
            dispatchDebounceMs = 0L,
            scope = testScope,
        )
        return TrackerParamsStreamingController(repository) to repository
    }

    private object NoOpLiveStreamServicePort : LiveStreamServicePort {
        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?,
        ): MapStreamingStartResult = MapStreamingStartResult.Started(trackerIds)

        override fun stopStreaming(context: Context): MapStreamingStopResult = MapStreamingStopResult.Stopped

        override fun persistedTargets(context: Context): Pair<Set<String>, String?> = emptySet<String>() to null
    }
}

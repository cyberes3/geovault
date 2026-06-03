package com.geovault.tracker.replay.runtime

import com.geovault.tracker.services.TrackingMotionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrafficJamEndToEndReplayTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun trafficJam_replaysThroughPositioningRuntime() {
        val session = CaptureReplaySessionLoader.load(SessionResource)
        val result = PositioningEndToEndReplayDriver(session).runReplay()
        try {
            assertEquals(
                TrackingMotionMode.valueOf(session.assertions.finalMode),
                result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
            )
            assertTrue(
                "expected at least ${session.assertions.minPersistedPoints} persisted points",
                result.persistedPointCount >= session.assertions.minPersistedPoints,
            )
            assertMotionRetryCount(session, result.telemetryLines)
            assertRequiredEvents(session, result.telemetryLines)
        } finally {
            result.close()
        }
    }

    private fun assertMotionRetryCount(
        session: CaptureReplaySessionDto,
        telemetryLines: List<String>,
    ) {
        val retryCount = telemetryLines.count { line -> line.contains("|auto_motion_retry|") }
        assertTrue(
            "expected at least ${session.assertions.expectedMotionRetryCountMin} auto-motion retries, saw $retryCount",
            retryCount >= session.assertions.expectedMotionRetryCountMin,
        )
    }

    private fun assertRequiredEvents(
        session: CaptureReplaySessionDto,
        telemetryLines: List<String>,
    ) {
        for (required in session.assertions.requiredEvents) {
            val startMs = session.wallBaseMs + required.fromWallOffsetMs
            val endMs = startMs + required.withinMs
            val matched = telemetryLines.any { line ->
                val timestampMs = line.substringBefore('|').toLongOrNull() ?: return@any false
                timestampMs in startMs..endMs &&
                    line.contains("|${required.kind}|") &&
                    line.contains("reason=${required.reason}") &&
                    line.contains("path=${required.path}")
            }
            assertTrue(
                "missing required ${required.kind} reason=${required.reason} path=${required.path}",
                matched,
            )
        }
    }

    private companion object {
        private const val SessionResource = "traffic_jam_2026_06_02"
    }
}

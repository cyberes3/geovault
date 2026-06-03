package com.geovault.tracker.replay.runtime

import com.geovault.tracker.services.TrackingMotionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WalkShortDriveWalkEndToEndReplayTest {
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
    fun walkShortDriveWalk_remainsWalkingAndResumesPersisting() {
        val session = CaptureReplaySessionLoader.load(SessionResource)
        val result = PositioningEndToEndReplayDriver(session).runReplay()
        try {
            assertEquals(
                TrackingMotionMode.WALKING,
                result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
            )
            assertTrue(
                "expected at least ${session.assertions.minPersistedPoints} persisted points",
                result.persistedPointCount >= session.assertions.minPersistedPoints,
            )
            val firstPostRelocationTrackPointMs = firstTrackPointAtOrAfter(
                telemetryLines = result.telemetryLines,
                wallTimeMs = session.wallBaseMs + RelocationOffsetMs,
            )
            assertNotNull(
                "expected a durable track_point after the short relocation",
                firstPostRelocationTrackPointMs,
            )
            assertTrue(
                "expected persistence to resume within the freshness window",
                firstPostRelocationTrackPointMs!! <= session.wallBaseMs + RelocationOffsetMs + FreshnessWindowMs,
            )
            val staleAnchorFallbacks = result.telemetryLines.count { line ->
                line.contains("|fallback_candidate_selected|") && line.contains("source=anchor")
            }
            assertEquals("fallback must not reinforce the stale anchor", 0, staleAnchorFallbacks)
        } finally {
            result.close()
        }
    }

    private fun firstTrackPointAtOrAfter(
        telemetryLines: List<String>,
        wallTimeMs: Long,
    ): Long? {
        return telemetryLines
            .asSequence()
            .filter { line -> line.contains("|track_point|") }
            .mapNotNull { line -> line.substringBefore('|').toLongOrNull() }
            .filter { timestampMs -> timestampMs >= wallTimeMs }
            .minOrNull()
    }

    private companion object {
        private const val SessionResource = "walk_short_drive_walk_2026_06_03"
        private const val RelocationOffsetMs = 135_000L
        private const val FreshnessWindowMs = 120_000L
    }
}

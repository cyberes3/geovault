package com.geovault.tracker.replay.runtime

import com.geovault.tracker.services.TrackingMotionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
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

            val maxJumpMeters = maxConsecutiveTrackPointJumpMeters(result.telemetryLines)
            assertTrue(
                "no committed track_point step should exceed ${MaxAllowedJumpMeters}m at mode-promotion; " +
                    "largest observed was %.0fm".format(maxJumpMeters),
                maxJumpMeters <= MaxAllowedJumpMeters,
            )
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

    private fun maxConsecutiveTrackPointJumpMeters(telemetryLines: List<String>): Double {
        data class LatLon(val lat: Double, val lon: Double)

        fun parseLine(line: String): LatLon? {
            val lat = Regex("""lat=([-\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = Regex("""lon=([-\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            return if (lat != null && lon != null) LatLon(lat, lon) else null
        }

        fun haversineMeters(a: LatLon, b: LatLon): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val sinDLat = sin(dLat / 2)
            val sinDLon = sin(dLon / 2)
            val h = sinDLat * sinDLat +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinDLon * sinDLon
            return 2 * r * asin(sqrt(h))
        }

        val points = telemetryLines
            .filter { it.contains("|track_point|") }
            .mapNotNull { parseLine(it) }

        return points.zipWithNext { a, b -> haversineMeters(a, b) }.maxOrNull() ?: 0.0
    }

    private companion object {
        private const val SessionResource = "walk_short_drive_walk_2026_06_03"
        private const val RelocationOffsetMs = 135_000L
        private const val FreshnessWindowMs = 120_000L
        private const val MaxAllowedJumpMeters = 300.0
    }
}

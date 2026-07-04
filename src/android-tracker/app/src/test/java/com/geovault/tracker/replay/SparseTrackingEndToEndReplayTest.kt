package com.geovault.tracker.replay

import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.replay.runtime.CaptureReplaySessionLoader
import com.geovault.tracker.replay.runtime.PositioningEndToEndReplayDriver
import com.geovault.tracker.services.TrackingMotionMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
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

/**
 * End-to-end replay coverage for sparse tracking (`TrackerSettings.sparseTracking`).
 *
 * [PositioningDensity.Sparse][com.geovault.tracker.positioning.config.PositioningDensity] itself
 * already has thorough unit coverage (`PositioningDensityTest`, `PositioningContextTest`,
 * `PositioningPolicyConfigTest`, `StationaryPingControllerTest`). What was missing was:
 *
 *   1. Any real capture-replay run with `sparseTracking=true` — every existing fixture under
 *      `replay/runtime/` hard-codes `sparseTracking=false`.
 *   2. Any coverage at all of [com.geovault.tracker.positioning.PositioningContextBuilder]'s
 *      `startSparseTrackingObserver`/`onSparseTrackingChanged` — the live wiring that reacts to a
 *      user toggling the setting *while tracking is active* (resets the elastic distance
 *      override, reapplies the live location request, reschedules the paused stationary ping).
 *      That code only ever runs against a real [com.geovault.tracker.positioning.PositioningRuntime],
 *      which — per this codebase's existing test conventions — is only ever constructed via this
 *      replay harness, never mocked directly.
 *
 * This suite reuses the synthetic `walk_short_drive_walk_2026_06_03` fixture (a calm, single-mode
 * WALKING session with a short mid-session relocation) rather than a new capture: sparse density
 * only changes cadence/interval scaling, not filter tuning, so no new raw-fix data is needed to
 * exercise it — the settings are simply overridden via `.copy()` on the loaded fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SparseTrackingEndToEndReplayTest {
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
    fun sparseTracking_doublesRuntimeCadence_forSameFinalMode() {
        val baselineSession = CaptureReplaySessionLoader.load(SessionResource)
        val sparseSession = baselineSession.copy(
            settings = baselineSession.settings.copy(sparseTracking = true),
        )

        val baselineResult = PositioningEndToEndReplayDriver(baselineSession).runReplay()
        val sparseResult = PositioningEndToEndReplayDriver(sparseSession).runReplay()
        try {
            assertEquals(
                "sparse tracking must not change motion classification",
                baselineResult.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
                sparseResult.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
            )

            val baselineContext = baselineResult.runtime.contextBuilder.currentPositioningRuntimeContext()
            val sparseContext = sparseResult.runtime.contextBuilder.currentPositioningRuntimeContext()

            assertEquals(
                "sparse must double the location interval",
                baselineContext.locationIntervalSec * 2,
                sparseContext.locationIntervalSec,
            )
            assertEquals(
                "sparse must double the point-freshness interval",
                baselineContext.pointFreshnessIntervalSec * 2,
                sparseContext.pointFreshnessIntervalSec,
            )
            assertEquals(
                "sparse must double the stationary probe interval",
                baselineContext.stationaryProbeIntervalMs * 2,
                sparseContext.stationaryProbeIntervalMs,
            )
        } finally {
            baselineResult.close()
            sparseResult.close()
        }
    }

    @Test
    fun sparseTracking_liveToggleMidSession_firesObserverAndDoublesCadence() {
        val session = CaptureReplaySessionLoader.load(SessionResource)
        var contextBeforeToggle: PositioningContext? = null

        val result = PositioningEndToEndReplayDriver(
            session = session,
            midReplayActions = listOf(
                ToggleOffsetMs to { runtime ->
                    contextBeforeToggle = runtime.contextBuilder.currentPositioningRuntimeContext()
                    runtime.deps.settingsRepository.setSparseTracking(true)
                },
            ),
        ).runReplay()
        try {
            val sparseChangedLines = result.telemetryLines.filter { "|sparse_tracking_changed|" in it }
            assertEquals(
                "expected exactly one sparse_tracking_changed event for the single live toggle; " +
                    "lines=$sparseChangedLines",
                1,
                sparseChangedLines.size,
            )
            val changedLine = sparseChangedLines.single()
            assertTrue("expected sparse=true in [$changedLine]", "sparse=true" in changedLine)
            val expectedProbeIntervalMs = StationaryPingController.DEFAULT_INTERVAL_MS * 2
            assertTrue(
                "expected probeIntervalMs=$expectedProbeIntervalMs in [$changedLine]",
                "probeIntervalMs=$expectedProbeIntervalMs" in changedLine,
            )

            val before = requireNotNull(contextBeforeToggle) { "mid-replay action never fired" }
            val after = result.runtime.contextBuilder.currentPositioningRuntimeContext()
            assertEquals(
                "location interval must double once the live toggle is applied",
                before.locationIntervalSec * 2,
                after.locationIntervalSec,
            )
            assertEquals(
                "stationary probe interval must double once the live toggle is applied",
                before.stationaryProbeIntervalMs * 2,
                after.stationaryProbeIntervalMs,
            )

            assertEquals(
                "toggling sparse tracking mid-session must not destabilize motion classification",
                TrackingMotionMode.WALKING,
                result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
            )
            assertTrue(
                "expected at least ${session.assertions.minPersistedPoints} persisted points after the live toggle",
                result.persistedPointCount >= session.assertions.minPersistedPoints,
            )
        } finally {
            result.close()
        }
    }

    @Test
    fun sparseTracking_noAnomaliesAcrossFullSession() {
        val session = CaptureReplaySessionLoader.load(SessionResource)
        val sparseSession = session.copy(settings = session.settings.copy(sparseTracking = true))

        val result = PositioningEndToEndReplayDriver(sparseSession).runReplay()
        try {
            assertEquals(
                TrackingMotionMode.WALKING,
                result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
            )
            assertTrue(
                "expected at least ${session.assertions.minPersistedPoints} persisted points even at half cadence",
                result.persistedPointCount >= session.assertions.minPersistedPoints,
            )
            assertEquals(
                "no local_stall_reanchor should fire in sparse mode over a calm walking session",
                0,
                result.telemetryLines.count { "|local_stall_reanchor|" in it },
            )

            val maxJumpMeters = maxConsecutiveTrackPointJumpMeters(result.telemetryLines)
            assertTrue(
                "no committed track_point step should exceed ${MaxAllowedJumpMeters}m in sparse mode; " +
                    "largest observed was %.0fm".format(maxJumpMeters),
                maxJumpMeters <= MaxAllowedJumpMeters,
            )
        } finally {
            result.close()
        }
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
            .filter { "|track_point|" in it }
            .mapNotNull { parseLine(it) }

        return points.zipWithNext { a, b -> haversineMeters(a, b) }.maxOrNull() ?: 0.0
    }

    private companion object {
        private const val SessionResource = "walk_short_drive_walk_2026_06_03"

        // Well before the fixture's short relocation event (offset 135_000ms), leaving ~195s of
        // tail activity to observe the post-toggle cadence.
        private const val ToggleOffsetMs = 60_000L

        private const val MaxAllowedJumpMeters = 300.0
    }
}

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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * End-to-end replay of a real driving→store-A-stop→driving→store-B-stop→driving session
 * recorded on 2026-06-08.
 *
 * The user drove to store A, walked inside for ~19 minutes, drove to store B (~2 min),
 * walked around for ~19 minutes, then drove home.  GPS visibility was poor inside both
 * buildings, causing the LocationFilter to consistently snap/suppress fixes
 * (filterIntervened=true) rather than accepting clean movement deltas.
 *
 * Three bugs produced incorrect behavior on the original build:
 *
 *   Bug 1 — Stationary detection stuck behind filterIntervened: stationaryUpdate() returned
 *   early with shouldPause=false as soon as filterIntervened=true, never honouring the
 *   PAUSE_THRESHOLD or the sensor-fusion confidence fast-advance path, so the stationary
 *   counter was perpetually stuck at 1 and GPS never paused inside the buildings.  Fixed by
 *   restructuring the filterIntervened branch to check both conditions.
 *
 *   Bug 2 — Demotion streak erased by onTick: while inside a building GPS fixes arrive only
 *   every ~2 minutes (DRIVING distance filter), and 5-second ticks between fixes were
 *   resetting consecutiveBelowLower=0, wiping the demotion streak and keeping the mode at
 *   DRIVING.  Fixed by removing the reset from onTick().
 *
 *   Bug 3 — onGpsPaused skipped demotion evidence: GPS pause events were not routed through
 *   setStateWithTransition, so they contributed no demotion streak.  Fixed by making
 *   onGpsPaused call setStateWithTransition(speed=0f).  In this replay the GPS-pause path
 *   itself does not fire (Robolectric has no significant-motion sensor), but the demotion
 *   still occurs via slow accepted fixes once Bug 2 is fixed.
 *
 * The fixture covers:
 *   • A short driving warm-up leg to establish filter context (offsets 0–80 s)
 *   • Store A stop (~200–1491 s, mode demotes DRIVING→BIKING→WALKING)
 *   • The driving leg between stops (~1491–1641 s)
 *   • Store B stop (~1641–2801 s, stationary detection fires via confidence fast-advance)
 *   • The driving departure (~2801–3111 s, mode re-promotes to DRIVING)
 *
 * The replay is driven once per class (see [replayData]).  All tests read from the cached
 * immutable result, keeping total test time to a single ~2 s run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StoreStopEndToEndReplayTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // Bug 1: Stationary detection through filterIntervened
    // -------------------------------------------------------------------------

    @Test
    fun storeStop_stationaryDetection_firesConfidenceFastAdvanceAtStoreA() {
        // The first indoor stop should fire confidence_fast_advance as soon as a clean snap
        // fix arrives after the anchor is established (adjustmentReason=UNCERTAINTY_SUPPRESSED,
        // confidence=0.65 > FAST_ADVANCE_SCORE).
        val inWindow = replayData.telemetryLines.filter { line ->
            "|stationary_update|" in line &&
                "shouldPause=true" in line &&
                "reason=confidence_fast_advance" in line &&
                (line.substringBefore('|').toLongOrNull() ?: 0L) <= StoreA_DepartureWallMs
        }
        assertTrue(
            "expected stationary_update shouldPause=true reason=confidence_fast_advance during " +
                "store-A stop (Bug 1: filterIntervened branch must not suppress confidence fast-advance)",
            inWindow.isNotEmpty(),
        )
    }

    @Test
    fun storeStop_stationaryDetection_firesConfidenceFastAdvanceAtStoreB() {
        // The second indoor stop exercises the filterIntervened path specifically:
        // localPointFresh=false keeps confirmedStillness=false, so filterIntervened=true, and
        // the old code returned early.  With the fix, confidence=0.65 advances the counter.
        val inWindow = replayData.telemetryLines.filter { line ->
            "|stationary_update|" in line &&
                "shouldPause=true" in line &&
                "reason=confidence_fast_advance" in line &&
                (line.substringBefore('|').toLongOrNull() ?: 0L).let {
                    it >= StoreB_ArrivalWallMs && it <= StoreB_DepartureWallMs
                }
        }
        assertTrue(
            "expected stationary_update shouldPause=true reason=confidence_fast_advance during " +
                "store-B stop (Bug 1: filterIntervened=true must not suppress confidence fast-advance)",
            inWindow.isNotEmpty(),
        )
    }

    @Test
    fun storeStop_stationaryCounter_advancesPastOne() {
        // Before Bug 1 was fixed, the stationary counter was stuck at 1 for the entire indoor
        // stop.  Verify it advances past 1.
        val anyAboveOne = replayData.telemetryLines
            .filter { "|stationary_update|" in it }
            .any { line ->
                val toField = line.substringAfter("to=").substringBefore(' ')
                (toField.toIntOrNull() ?: 0) > 1
            }
        assertTrue(
            "stationary consecutive counter must advance past 1 " +
                "(Bug 1: filterIntervened early return kept counter perpetually stuck at 1)",
            anyAboveOne,
        )
    }

    // -------------------------------------------------------------------------
    // Bug 2: Demotion streak accumulation across GPS-quiet gaps (store A stop)
    // -------------------------------------------------------------------------

    @Test
    fun storeAStop_modeDeomotesToBiking() {
        // With 2-minute GPS intervals inside the building, onTick fired ~24 times between
        // each fix.  Before Bug 2 was fixed, each tick reset consecutiveBelowLower to 0.
        // With the fix, the streak persists and mode demotes DRIVING→BIKING.
        val demotedBeforeDeparture = replayData.telemetryLines.any { line ->
            "|auto_mode_changed|" in line &&
                "mode=BIKING" in line &&
                (line.substringBefore('|').toLongOrNull() ?: Long.MAX_VALUE) <= StoreA_DepartureWallMs
        }
        assertTrue(
            "mode must demote to BIKING during store-A stop " +
                "(Bug 2: onTick must not erase the demotion streak across 2-min GPS gaps)",
            demotedBeforeDeparture,
        )
    }

    @Test
    fun storeAStop_modeFullyDemotesToWalking() {
        // After demoting to BIKING, subsequent slow-fix decisions cascade mode to WALKING,
        // matching the in-store pedestrian activity.
        val modeChanges = replayData.telemetryLines.filter { "|auto_mode_changed|" in it }
        val bikingIndex = modeChanges.indexOfFirst {
            "mode=BIKING" in it &&
                (it.substringBefore('|').toLongOrNull() ?: Long.MAX_VALUE) <= StoreA_DepartureWallMs
        }
        assertTrue("expected BIKING demotion at store A before WALKING cascade", bikingIndex >= 0)

        val walkingAfterBiking = modeChanges.drop(bikingIndex + 1).any {
            "mode=WALKING" in it &&
                (it.substringBefore('|').toLongOrNull() ?: Long.MAX_VALUE) <= StoreA_DepartureWallMs
        }
        assertTrue(
            "mode must cascade BIKING→WALKING during store-A stop " +
                "(user was walking; slow-fix streak must continue accumulating after BIKING demotion)",
            walkingAfterBiking,
        )
    }

    // -------------------------------------------------------------------------
    // No GPS anomaly jumps while parked
    // -------------------------------------------------------------------------

    @Test
    fun storeAStop_noTrackPointJumpWhileParked() {
        assertNoLargeDisplacementWhileParked(
            arrivalWallMs = StoreA_ArrivalWallMs,
            departureWallMs = StoreA_DepartureWallMs,
            maxDisplacementMeters = 300.0,
            stopName = "store A",
        )
    }

    @Test
    fun storeBStop_noTrackPointJumpWhileParked() {
        assertNoLargeDisplacementWhileParked(
            arrivalWallMs = StoreB_ArrivalWallMs,
            departureWallMs = StoreB_DepartureWallMs,
            maxDisplacementMeters = 300.0,
            stopName = "store B",
        )
    }

    // -------------------------------------------------------------------------
    // Post-stop: mode re-promotes to DRIVING on departure
    // -------------------------------------------------------------------------

    @Test
    fun storeBStop_modeRepromotesToDrivingAfterDeparture() {
        assertEquals(
            "session must end in DRIVING mode after departure from store B",
            TrackingMotionMode.DRIVING,
            replayData.finalMode,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assertNoLargeDisplacementWhileParked(
        arrivalWallMs: Long,
        departureWallMs: Long,
        maxDisplacementMeters: Double,
        stopName: String,
    ) {
        data class Pt(val wallMs: Long, val lat: Double, val lon: Double)

        val points = replayData.telemetryLines
            .filter { "|track_point|" in it }
            .mapNotNull { line ->
                val ts = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                val lat = line.substringAfter("lat=").substringBefore(' ').toDoubleOrNull()
                    ?: return@mapNotNull null
                val lon = line.substringAfter("lon=").substringBefore(' ').toDoubleOrNull()
                    ?: return@mapNotNull null
                Pt(ts, lat, lon)
            }

        val inWindow = points.filter { it.wallMs in arrivalWallMs..departureWallMs }

        assertTrue(
            "replay must emit at least one track_point in the $stopName parked window",
            inWindow.isNotEmpty(),
        )

        // Use the arrival fix as the parked anchor and verify that no subsequent parked
        // track_point strays more than maxDisplacementMeters from it.  We intentionally
        // skip the comparison against the prior (driving) fix; we only care that GPS does
        // not teleport while the device is stationary at this stop.
        val anchor = inWindow.first()
        for (pt in inWindow.drop(1)) {
            val distM = haversineMeters(anchor.lat, anchor.lon, pt.lat, pt.lon)
            assertTrue(
                "$stopName: track_point at wallMs=${pt.wallMs} is ${"%.0f".format(distM)} m from " +
                    "the parked anchor at wallMs=${anchor.wallMs} — exceeds " +
                    "${maxDisplacementMeters.toInt()} m GPS-anomaly limit while parked",
                distM <= maxDisplacementMeters,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Shared replay result
    // -------------------------------------------------------------------------

    private data class ReplayData(
        val telemetryLines: List<String>,
        val finalMode: TrackingMotionMode,
    )

    private companion object {
        private const val SessionResource = "store_stop_2026_06_08"

        // Fixture wall-clock base (offset 0 s in the fixture)
        private const val WallBaseMs = 1780931563721L

        // Store A stop window (offsets 200–1491 s)
        private const val StoreA_ArrivalWallMs = WallBaseMs + 200_049L
        private const val StoreA_DepartureWallMs = WallBaseMs + 1_491_044L

        // Store B stop window (offsets 1641–2801 s)
        private const val StoreB_ArrivalWallMs = WallBaseMs + 1_641_052L
        private const val StoreB_DepartureWallMs = WallBaseMs + 2_801_089L

        private val replayData: ReplayData by lazy {
            val session = CaptureReplaySessionLoader.load(SessionResource)
            PositioningEndToEndReplayDriver(session).runReplay().use { result ->
                ReplayData(
                    telemetryLines = result.telemetryLines,
                    finalMode = result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
                )
            }
        }

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }
    }
}

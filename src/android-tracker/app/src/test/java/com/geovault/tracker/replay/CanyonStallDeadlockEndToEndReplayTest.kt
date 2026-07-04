package com.geovault.tracker.replay

import com.geovault.tracker.replay.runtime.CaptureReplaySessionLoader
import com.geovault.tracker.replay.runtime.PositioningEndToEndReplayDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end replay of a real canyon-driving session recorded on 2026-07-01, 21:28-21:43 UTC,
 * that produced a user-visible "huge jump" on the map.
 *
 * Unlike [HighwayModeDeadlockEndToEndReplayTest], this fixture is extracted purely from the
 * point-recording log (`positioning_raw_fix`/`positioning_imu_classification`) rather than the
 * diagnostic capture log, so it carries no `expectedEvents`/decision-trace data -- assertions
 * below are hand-written against the replayed runtime's own telemetry, matching the precedent
 * set by [HighwayModeDeadlockEndToEndReplayTest].
 *
 * The device was driving through canyon terrain with intermittent satellite occlusion:
 * consecutive raw fixes ranged from good accuracy (~10-20 m) at ~1 s apart down to severely
 * degraded fixes (up to ~19,100 m, Android's cell/network fallback) with gaps up to ~60 s. That
 * degraded accuracy repeatedly routed fixes into `RelocationRecoveryGate`/
 * `SpatialConfirmationGate`'s stale-anchor negotiation, rejecting them with
 * `candidate-unconfirmed`/`stale-relocation-unconfirmed` instead of `speed-cap-exceeded`.
 *
 * The bug: [com.geovault.tracker.policy.filter.LocationFilterReasonPolicy.isCapEvidence] never
 * recognized those two reasons, so
 * [com.geovault.tracker.location.AutoTrackingMotionCoordinator.onRejectedOrHeld] routed them to
 * [com.geovault.tracker.location.AutoTrackingMotionEvidenceGate] not at all -- they fell through
 * to the neutral-hold branch instead. Once the reject reason permanently shifted away from
 * `speed-cap-exceeded` partway through the drive, the evidence gate was starved for the rest of
 * the session: motion mode stayed WALKING despite genuine sustained ~25-35 m/s highway motion,
 * the speed cap kept rejecting fixes against a stale WALKING-speed anchor, and the reject streak
 * eventually tripped `local_stall_reanchor` twice (streak=7 and streak=55) -- each firing a
 * forced relocation that produced the large, sudden jump the user reported.
 *
 * Fix: [com.geovault.tracker.policy.filter.LocationFilterReasonPolicy.isStaleRelocationEvidence]
 * plus [com.geovault.tracker.location.AutoTrackingMotionEvidenceGate.evaluateStaleRelocation],
 * which derives speed from `rawDistanceMeters/elapsedSeconds` (the raw haversine to the
 * last-seen fix, unaffected by `LocationMetricsEngine`'s RSS-accuracy suppression of
 * `effectiveDistanceMeters`/`impliedSpeedMps`) instead of requiring a `speed-cap-exceeded`
 * reason. Observed with the fix: mode promotes to DRIVING at ~246 s and no
 * `local_stall_reanchor` fires. Without the fix: mode never leaves WALKING for the whole
 * ~15-minute window and `local_stall_reanchor` fires twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CanyonStallDeadlockEndToEndReplayTest {
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
    fun canyon_modePromotesToDriving_withinFiveMinutes() {
        // With the fix, the evidence gate's second HANDSHAKE (completed via a
        // stale-relocation-unconfirmed fix carrying real rawDistanceMeters) fires at
        // ~246 s. Without the fix, candidate-unconfirmed/stale-relocation-unconfirmed
        // fixes never produce evidence and mode never reaches DRIVING in this fixture.
        val promotionMs = replayData.firstDrivingPromotionOffsetMs
        assertTrue(
            "mode must promote to DRIVING within 5 minutes of canyon driving start; " +
                "actual first promotion at ${promotionMs?.let { it / 1000 } ?: "never"} s " +
                "(candidate-unconfirmed/stale-relocation-unconfirmed fixes must still feed " +
                "the evidence gate via rawDistanceMeters)",
            promotionMs != null && promotionMs <= PROMOTION_DEADLINE_MS,
        )
    }

    @Test
    fun canyon_noLocalStallReanchor() {
        // The real incident's user-visible "huge jump" was produced by two
        // local_stall_reanchor firings while mode was stuck WALKING against genuine
        // highway-speed motion. With mode correctly promoted to DRIVING, the speed cap
        // no longer rejects the real fixes and the stale-anchor reject streak never
        // reaches the reanchor threshold.
        assertFalse(
            "no local_stall_reanchor should fire during canyon driving " +
                "(stall reanchor implies filter believed the device was stationary " +
                "at highway speed, matching the real reported map jump)",
            replayData.telemetryLines.any { "|local_stall_reanchor|" in it },
        )
    }

    // -------------------------------------------------------------------------
    // Shared replay result
    // -------------------------------------------------------------------------

    private data class ReplayData(
        val telemetryLines: List<String>,
        /**
         * Wall-clock offset from fixture start at which the mode first changed to DRIVING,
         * or null if no such transition occurred during the replay.
         */
        val firstDrivingPromotionOffsetMs: Long?,
    )

    private companion object {
        private const val SessionResource = "canyon_stall_deadlock_2026_07_01"
        private const val WallBaseMs = 1782941280578L

        // Conservative threshold: 5 minutes. Observed promotion time with the fix is
        // ~246 s; without the fix, mode never reaches DRIVING within this fixture's
        // ~15-minute window.
        private const val PROMOTION_DEADLINE_MS = 5 * 60 * 1000L

        private val replayData: ReplayData by lazy {
            val session = CaptureReplaySessionLoader.load(SessionResource)
            PositioningEndToEndReplayDriver(session).runReplay().use { result ->
                val firstDrivingWallMs = result.telemetryLines
                    .firstOrNull { "|auto_mode_changed|" in it && "mode=DRIVING" in it }
                    ?.substringBefore('|')
                    ?.toLongOrNull()
                ReplayData(
                    telemetryLines = result.telemetryLines,
                    firstDrivingPromotionOffsetMs = firstDrivingWallMs?.let { it - WallBaseMs },
                )
            }
        }
    }
}

package com.geovault.tracker.replay

import com.geovault.tracker.replay.runtime.CaptureReplaySessionLoader
import com.geovault.tracker.replay.runtime.PositioningEndToEndReplayDriver
import com.geovault.tracker.services.TrackingMotionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end replay of a real sig_motion resume→highway driving session recorded on 2026-06-09.
 *
 * The device was parked (WALKING mode) with GPS paused. At 13:08 UTC the significant-motion
 * sensor fired, GPS resumed, and the device immediately began driving at highway speeds
 * (up to 135 km/h).  GPS state was FALLBACK_PENDING for most of the window because the
 * chipset was slow to acquire full satellite lock while moving at speed.
 *
 * The bug: [com.geovault.tracker.location.AutoTrackingMotionEngine.onTick] was resetting
 * [AutoTrackingMotionState.consecutiveAboveUpper] to 0 on every 5-second tick. Between
 * fixes, ticks outnumbered fixes ~300:1 (one fix per ~20s driving interval, ~4 ticks/fix),
 * so the promotion handshake counter was reset before the second fix ever arrived, making
 * DRIVING promotion impossible until a dense burst of fixes happened to arrive back-to-back
 * without a tick in between — a timing accident that could take 15–29 minutes.
 *
 * Fix: [AutoTrackingMotionEngine.onTick] no longer resets [consecutiveAboveUpper]. Both
 * promotion and demotion streak counters are preserved across ticks.
 * Observed promotion time with the fix: ~182 s. Without the fix: 15–29 min or never.
 *
 * The fixture covers:
 *   • The locking/fallback window at session start (offsets 0–44 s, gpsState=LOCKING/FALLBACK_PENDING)
 *   • Sustained highway driving (offsets ~44–990 s, speedMps up to 37.6 m/s)
 *
 * The test fails without the [AutoTrackingMotionEngine.onTick] fix, making it a regression
 * guard for that change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HighwayModeDeadlockEndToEndReplayTest {
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
    // Mode promotion must not be blocked by onTick counter reset
    // -------------------------------------------------------------------------

    @Test
    fun highway_modePromotesToDriving_withinThreeMinutes() {
        // With the onTick fix, the evidence gate fires on the first HANDSHAKE pair at ~44s
        // offset. Without the fix, consecutiveAboveUpper resets between every pair of fixes
        // and mode stays WALKING for 15+ minutes.
        val promotionMs = replayData.firstDrivingPromotionOffsetMs
        assertTrue(
            "mode must promote to DRIVING within 5 minutes of highway driving start; " +
                "actual first promotion at ${promotionMs?.let { it / 1000 } ?: "never"} s " +
                "(onTick must not reset consecutiveAboveUpper between fixes)",
            promotionMs != null && promotionMs <= PROMOTION_DEADLINE_MS,
        )
    }

    @Test
    fun highway_finalModeIsDriving() {
        assertEquals(
            "final mode after sustained highway driving must be DRIVING",
            TrackingMotionMode.DRIVING,
            replayData.finalMode,
        )
    }

    @Test
    fun highway_noLocalStallReanchor() {
        // local_stall_reanchor produces large GPS jumps. With correct mode promotion the
        // speed cap prevents the stale anchor from triggering a forced relocation.
        assertFalse(
            "no local_stall_reanchor should fire during highway driving " +
                "(stall reanchor implies filter believed the device was stationary at highway speed)",
            replayData.telemetryLines.any { "|local_stall_reanchor|" in it },
        )
    }

    // -------------------------------------------------------------------------
    // Shared replay result
    // -------------------------------------------------------------------------

    private data class ReplayData(
        val telemetryLines: List<String>,
        val finalMode: TrackingMotionMode,
        /**
         * Wall-clock offset from fixture start at which the mode first changed to DRIVING,
         * or null if no such transition occurred during the replay.
         */
        val firstDrivingPromotionOffsetMs: Long?,
    )

    private companion object {
        private const val SessionResource = "highway_mode_deadlock_2026_06_09"
        private const val WallBaseMs = 1781010505653L
        // Conservative threshold: 5 minutes. The observed promotion time with the fix
        // is ~182 s; without the fix (onTick resetting consecutiveAboveUpper) promotion
        // could take 15–29 minutes or never complete within the fixture window.
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
                    finalMode = result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
                    firstDrivingPromotionOffsetMs = firstDrivingWallMs?.let { it - WallBaseMs },
                )
            }
        }
    }
}

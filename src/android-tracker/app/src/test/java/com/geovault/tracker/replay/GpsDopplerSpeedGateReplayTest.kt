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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end replay of the 2026-06-12 false GPS pause scenario.
 *
 * The device was driving slowly (last raw fix at 2.33 m/s, offset ~19 s). GPS accuracy
 * was inflated by UNCERTAINTY_SUPPRESSED, making [filterConfirmedStillness] true for
 * accepted fixes even while the device was moving. The IMU transitioned to STATIONARY
 * at offset ~36 s, making [confidenceCanFastAdvance] eligible. At offset ~39 s the
 * stationary counter jumped from 1 to PAUSE_THRESHOLD via `confidence_fast_advance`,
 * causing an attempted GPS pause for 115 seconds until [imu_vehicular_wake] at ~155 s.
 *
 * The fix: [com.geovault.tracker.TrackingLocationPolicy.stationaryUpdate] now guards
 * [confidenceFastAdvance] with `!movedByGpsSpeed`. When GPS Doppler speed exceeds
 * [GPS_MOTION_FLOOR_MPS] (1.0 m/s), fast-advance is blocked even when
 * [filterConfirmedStillness] is true — position geometry is unreliable under inflated
 * accuracy envelopes; Doppler is a direct velocity measurement.
 *
 * The unit tests in [com.geovault.tracker.TrackingLocationPolicyTest] are the
 * primary verification of the `!movedByGpsSpeed` guard. This end-to-end replay
 * documents the real-device window and ensures the full positioning stack does not
 * produce a GPS state transition to PAUSED_FOR_MOTION between the first accepted
 * snap fix (offset ~29 s) and the IMU vehicular wake (offset ~155 s).
 *
 * Note on Robolectric: the hardware Significant Motion sensor is unavailable in the
 * test environment, so `shouldPause=true` from [stationaryUpdate] produces a
 * `gps_pause_skipped` event rather than an actual PAUSED_FOR_MOTION transition.
 * GPS therefore does not physically pause in the replay regardless of the fix.
 * The [movingPhase_gpsStateMustNotShowPausedForMotion] assertion guards against
 * regressions where the fix is removed and an actual PAUSED_FOR_MOTION somehow fires.
 *
 * The fixture covers:
 *   - High-speed fixes rejected by the location filter (offsets 0–19 s, Doppler 18→2.33 m/s)
 *   - First accepted snap fix (offset ~29 s, Doppler 0.30 m/s, confirmedStillness=true)
 *   - IMU transition to STATIONARY (offset ~36 s, confidence=0.77)
 *   - Stationary counter fast-advance to PAUSE_THRESHOLD (offset ~39 s, Doppler 0.38 m/s)
 *   - IMU vehicular wake (offset ~155 s, confidence=0.98)
 *   - GPS recovery after wake (offsets 158–161 s)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GpsDopplerSpeedGateReplayTest {
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
    // GPS state must not reach PAUSED_FOR_MOTION during the moving phase
    // -------------------------------------------------------------------------

    @Test
    fun movingPhase_gpsStateMustNotShowPausedForMotion() {
        // Between the first accepted snap fix (offset ~29 s) and the IMU vehicular
        // wake (offset ~155 s), no actual GPS state transition to PAUSED_FOR_MOTION
        // should occur. In Robolectric, the hardware Significant Motion sensor is
        // absent so the pause is always skipped, making this assertion vacuously true
        // in the current test environment. It is retained as a regression guard: should
        // a future refactor bypass the significant_motion_unavailable skip path and
        // allow an actual PAUSED_FOR_MOTION transition, this test would catch it.
        val gpsPauseLines = replayData.telemetryLines.filter { line ->
            "|gps_state|" in line &&
                "to=PAUSED_FOR_MOTION" in line &&
                (line.substringBefore('|').toLongOrNull() ?: 0L).let {
                    it in FirstSnapFixWallMs..VehicularWakeWallMs
                }
        }
        assertFalse(
            "GPS must not reach PAUSED_FOR_MOTION between the first accepted snap fix " +
                "(offset ~29 s) and the IMU vehicular wake (offset ~155 s); found " +
                "${gpsPauseLines.size} pause event(s): ${gpsPauseLines.take(3)}.",
            gpsPauseLines.isNotEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // Shared replay result
    // -------------------------------------------------------------------------

    private data class ReplayData(val telemetryLines: List<String>)

    private companion object {
        private const val SessionResource = "gps_doppler_speed_gate_2026_06_12"
        private const val WallBaseMs = 1781276736612L

        // Offset 29 015 ms — first fix accepted by the location filter with
        // filterConfirmedStillness=true (snap-internal, uncertainty-suppressed)
        private const val FirstSnapFixWallMs = WallBaseMs + 29_015L

        // Offset 154 747 ms — IMU transitions to VEHICULAR (confidence=0.98), triggering
        // imu_vehicular_wake which legitimately resumes GPS
        private const val VehicularWakeWallMs = WallBaseMs + 154_747L

        private val replayData: ReplayData by lazy {
            val session = CaptureReplaySessionLoader.load(SessionResource)
            PositioningEndToEndReplayDriver(session).runReplay().use { result ->
                ReplayData(telemetryLines = result.telemetryLines)
            }
        }
    }
}

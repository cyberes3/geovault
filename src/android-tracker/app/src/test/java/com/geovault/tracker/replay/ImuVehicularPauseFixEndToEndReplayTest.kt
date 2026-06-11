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
 * End-to-end replay of the 2026-06-11 second-departure scenario.
 *
 * The device was parked (coordinates anonymized in fixture) after arriving from an
 * earlier drive. The user then departed on foot briefly (IMU=PEDESTRIAN at offsets
 * 120–136 s) before getting into a vehicle (IMU=VEHICULAR confidence=1.0 at 171 s).
 *
 * The bug (pre-fix): [com.geovault.tracker.TrackingLocationPolicy.stationaryUpdate]
 * did not treat [com.geovault.tracker.positioning.motion.ImuClassification.VEHICULAR]
 * as an active-speed-hint. Once the user settled into the vehicle but before actually
 * driving away, GPS fixes continued to report low speeds (0.21–0.25 m/s). The stationary
 * counter advanced to the pause threshold and GPS paused at ~195 s. The hardware
 * significant-motion sensor then took another 376 s to fire, causing a 6-minute GPS
 * blackout and a large position jump on the map.
 *
 * Fix 1 — VEHICULAR prevents GPS pause: [effectiveActiveSpeedHint] now includes
 * [com.geovault.tracker.positioning.motion.ImuClassification.VEHICULAR], resetting
 * the stationary counter whenever vehicular motion is detected, regardless of reported
 * GPS speed.
 *
 * Fix 2 — VEHICULAR wakes GPS from pause: if GPS does pause despite the above
 * (e.g., via [com.geovault.tracker.TrackingLocationPolicy.filterConfirmedStillness]),
 * a transition to VEHICULAR with confidence ≥ 0.5 calls
 * [com.geovault.tracker.positioning.collection.GpsCollectionSubsystem.resumeGps]
 * ("imu_vehicular_wake"), bypassing the slow significant-motion sensor.
 *
 * This test verifies Fix 1 end-to-end using real captured data.
 * Fix 2 is covered by unit tests in
 * [com.geovault.tracker.positioning.motion.ImuAttentionBoostTest].
 *
 * The fixture covers:
 *   - GPS locking window at session start (offsets 0–129 s)
 *   - PEDESTRIAN phase (offsets 120–136 s, IMU=PEDESTRIAN)
 *   - VEHICULAR onset (offset 171 s, confidence=1.0)
 *   - Low-speed vehicular phase (offsets 175–195 s, speeds 0.21–0.25 m/s)
 *   - Real significant-motion resume boundary (offset 571 s)
 *   - Post-resume running phase (offsets 596–734 s)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImuVehicularPauseFixEndToEndReplayTest {
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
    // Fix 1: VEHICULAR classification must prevent GPS pause
    // -------------------------------------------------------------------------

    @Test
    fun vehicularPhase_gpsMustNotPause() {
        // Between the VEHICULAR onset at offset 171 s and the significant-motion resume
        // boundary recorded on the real device at offset 571 s, no PAUSE_FOR_MOTION event
        // should fire. Before the fix, the stationary counter incremented freely during
        // the low-speed VEHICULAR phase (speeds 0.21–0.25 m/s) and GPS paused at ~195 s.
        // With the fix, VEHICULAR acts as an active-speed-hint and resets the counter.
        val gpsPauseLines = replayData.telemetryLines.filter { line ->
            "|gps_state|" in line &&
                "to=PAUSED_FOR_MOTION" in line &&
                (line.substringBefore('|').toLongOrNull() ?: 0L).let {
                    it in VehicularOnsetWallMs..SigMotionResumeWallMs
                }
        }
        assertFalse(
            "GPS must not pause during the VEHICULAR phase (offsets 171–571 s); " +
                "found ${gpsPauseLines.size} pause event(s): ${gpsPauseLines.take(3)}. " +
                "VEHICULAR classification must act as an active-speed-hint and reset the " +
                "stationary counter even at low GPS-reported speeds (0.21–0.25 m/s).",
            gpsPauseLines.isNotEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // Shared replay result
    // -------------------------------------------------------------------------

    private data class ReplayData(val telemetryLines: List<String>)

    private companion object {
        private const val SessionResource = "imu_vehicular_pause_fix_2026_06_11"
        private const val WallBaseMs = 1781188832024L

        // Offset 171 633 ms = first VEHICULAR classification (confidence=1.0)
        private const val VehicularOnsetWallMs = WallBaseMs + 171_633L

        // Offset 571 269 ms = first LOCKING fix in recording, after real significant-motion
        private const val SigMotionResumeWallMs = WallBaseMs + 571_269L

        private val replayData: ReplayData by lazy {
            val session = CaptureReplaySessionLoader.load(SessionResource)
            PositioningEndToEndReplayDriver(session).runReplay().use { result ->
                ReplayData(telemetryLines = result.telemetryLines)
            }
        }
    }
}

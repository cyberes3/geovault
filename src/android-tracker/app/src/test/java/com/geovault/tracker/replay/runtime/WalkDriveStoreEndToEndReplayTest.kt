package com.geovault.tracker.replay.runtime

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
 * End-to-end replay of a real walk→drive→store-stop→drive session recorded on 2026-06-07.
 *
 * This session exposed two positioning bugs that were fixed:
 *
 *   Bug 1 — Jump on mode promotion: when a rejected fix triggered a WALKING→BIKING or BIKING→DRIVING
 *   mode change, the same fix was immediately re-ingested against the new profile ("auto_motion_retry").
 *   The stale previousAccepted anchor caused the full accumulated distance to be committed in one
 *   step, producing visible 400–700 m jumps.  Fixed by seeding the filter at the evidence-fix
 *   location instead of re-ingesting ("seed-and-skip").
 *
 *   Bug 2 — Mode oscillation while stationary: in MotionSubsystem, when stationary detection
 *   triggered pauseGps(), onGpsPaused() ran first but was immediately negated by onAcceptedFix(0)
 *   called unconditionally afterwards.  This fed zero-speed samples into the motion smoother,
 *   accumulating consecutive-demotion counts and causing DRIVING→BIKING→WALKING while the car was
 *   parked.  Fixed by skipping onAcceptedFix when GPS is being paused.
 *
 * The replay is driven once for the class (see [replayData]) and all tests read from the
 * cached immutable result, keeping total test time close to a single ~3 s run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WalkDriveStoreEndToEndReplayTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // Must be set before replayData is accessed for the first time so that the
        // coroutines launched by PositioningEndToEndReplayDriver dispatch correctly.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun walkDriveStore_modePromotionUsesSeedingNotRetry() {
        val lines = replayData.telemetryLines

        // (1) At least one seeded event must appear for each promotion in the session
        //     (WALKING→BIKING and BIKING→DRIVING both occur in this window).
        val seededEvents = lines.filter { "|auto_motion_seeded|" in it }
        assertTrue("expected auto_motion_seeded events for mode promotions", seededEvents.size >= 2)

        // (2) The pre-fix event name must be gone entirely.
        val retryEvents = lines.filter { "|auto_motion_retry|" in it }
        assertEquals("auto_motion_retry must not appear after seed-and-skip fix", 0, retryEvents.size)

        // (3) No track_point was emitted at any seed timestamp.
        //     The old re-ingest path would commit the fix immediately, producing a track_point
        //     with the same wall-ms as the rejected fix that triggered the promotion.
        val trackPointTimestamps = lines
            .filter { "|track_point|" in it }
            .mapNotNull { it.substringBefore('|').toLongOrNull() }
            .toSet()
        for (seedLine in seededEvents) {
            val seedMs = seedLine.substringBefore('|').toLongOrNull() ?: continue
            assertFalse(
                "track_point must not be emitted at seed timestamp $seedMs " +
                    "(seed-and-skip must not emit; re-ingest would have done so)",
                seedMs in trackPointTimestamps,
            )
        }
    }

    @Test
    fun walkDriveStore_modeNeverReturnsToWalkingAfterFirstBikingPromotion() {
        // Verifies that once the session reaches BIKING the mode never falls all the way back to
        // WALKING.  In the replay the GPS-pause path does not fire (Robolectric has no significant-
        // motion sensor and the pause-eligibility policy gates on it when significantDataOnly=true),
        // so this test does NOT directly exercise the onGpsPaused/onAcceptedFix override bug.
        // The dedicated unit test AutoTrackingMotionEngineTest
        //   .onGpsPaused_doesNotDecaySmoothedSpeed_unlikeAcceptedFixAtZero
        // and MotionSubsystemTest.acceptedFix_whenGpsBeingPaused_skipsOnAcceptedFix cover that path.
        val modeChangedLines = replayData.telemetryLines.filter { "|auto_mode_changed|" in it }
        val firstBikingIndex = modeChangedLines.indexOfFirst { "mode=BIKING" in it }
        assertTrue("expected at least one BIKING promotion in session", firstBikingIndex >= 0)

        val walkingAfterBiking = modeChangedLines
            .drop(firstBikingIndex + 1)
            .any { "mode=WALKING" in it }
        assertFalse(
            "mode must not demote to WALKING after the first BIKING promotion",
            walkingAfterBiking,
        )
    }

    @Test
    fun walkDriveStore_sessionEndsInDrivingMode() {
        assertEquals(TrackingMotionMode.DRIVING, replayData.finalMode)
    }

    // ---------------------------------------------------------------------------
    // Shared replay result
    // ---------------------------------------------------------------------------

    /**
     * Immutable snapshot of the positioning replay, computed lazily on first access and shared
     * across all test methods in the class.  Using [lazy] avoids re-running the ~3 s replay for
     * each [Test] method while keeping each method independently named for failure reporting.
     *
     * Robolectric does not reset JVM-level statics between test methods, so this value is stable
     * for the lifetime of the test class in a given JVM.  The result is extracted as plain data
     * ([List<String>], [TrackingMotionMode]) so it carries no Android lifecycle dependency once
     * the [PositioningEndToEndReplayResult] is closed.
     */
    private data class ReplayData(
        val telemetryLines: List<String>,
        val finalMode: TrackingMotionMode,
    )

    private companion object {
        private const val SessionResource = "walk_drive_store_2026_06_07"

        private val replayData: ReplayData by lazy {
            val session = CaptureReplaySessionLoader.load(SessionResource)
            PositioningEndToEndReplayDriver(session).runReplay().use { result ->
                ReplayData(
                    telemetryLines = result.telemetryLines,
                    finalMode = result.runtime.deps.autoTrackingMotionEngine.snapshot().mode,
                )
            }
        }
    }
}

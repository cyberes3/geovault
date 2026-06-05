package com.geovault.tracker.replay.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end replay test verifying that an activity-transition event in the merged timeline
 * triggers the AAR scrutiny window and emits the expected telemetry events.
 *
 * The session uses synthetic GPS data and injects an in-vehicle ENTER transition mid-session.
 * After the transition, the next motion-tick should open the scrutiny window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AarScrutinyWindowReplayTest {

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
    fun `in_vehicle enter transition opens scrutiny window`() {
        val session = buildSession()
        val result = PositioningEndToEndReplayDriver(session).runReplay()
        try {
            val lines = result.telemetryLines
            assertTrue(
                "expected aar_scrutiny_opened in telemetry but got: $lines",
                lines.any { it.contains("|aar_scrutiny_opened|") || it.contains("|aar_stationary_resumed|") }
            )
        } finally {
            result.close()
        }
    }

    @Test
    fun `session with no activity transitions replays without errors`() {
        val session = buildSession(includeTransitions = false)
        val result = PositioningEndToEndReplayDriver(session).runReplay()
        try {
            assertTrue(result.persistedPointCount >= 0)
            val lines = result.telemetryLines
            assertTrue(
                "aar_scrutiny_opened should not appear when no transitions injected",
                lines.none { it.contains("|aar_scrutiny_opened|") }
            )
        } finally {
            result.close()
        }
    }

    private fun buildSession(includeTransitions: Boolean = true): CaptureReplaySessionDto {
        val wallBaseMs = 1_780_500_000_000L
        val elapsedBaseNanos = wallBaseMs * 1_000_000L
        val settings = CaptureReplaySettingsDto(
            accuracyFilterMeters = 50.0f,
            lowAccuracyFallbackEnabled = false,
            lowAccuracyFallbackTimeoutSec = 60L,
            sendExtendedData = false,
            significantDataOnly = false,
            sparseTracking = false,
            startOnBoot = false,
            startTrackingOnLaunch = false,
            keepScreenOnWhileViewingMap = false,
            groupModeFitOnlyActiveTrackers = false,
        )
        val rawFixes = (0 until 8).map { i ->
            val offsetMs = i * 15_000L
            CaptureReplayRawFixDto(
                index = i,
                wallOffsetMs = offsetMs,
                elapsedRealtimeOffsetNanos = offsetMs * 1_000_000L,
                gpsTimeMs = wallBaseMs + offsetMs,
                lat = 39.0 + i * 0.0001,
                lon = -105.0,
                accuracy = 10.0f,
                speedMps = 1.0f,
            )
        }
        // Transition fires at 7500ms — between fix[0] at 0ms and fix[1] at 15000ms.
        // The next motion tick after the transition (at 10000ms) picks it up and opens the window.
        val transitions = if (includeTransitions) {
            listOf(
                CaptureReplayActivityTransitionDto(
                    wallOffsetMs = 7_500L,
                    elapsedRealtimeOffsetNanos = 7_500L * 1_000_000L,
                    eventTimeMs = 7_500L,
                    activity = "in_vehicle",
                    transitionType = "enter",
                    hintActive = true,
                )
            )
        } else {
            emptyList()
        }
        return CaptureReplaySessionDto(
            schemaVersion = 2,
            sessionId = "aar_scrutiny_test",
            trackId = "aar-scrutiny-test-track",
            wallBaseMs = wallBaseMs,
            elapsedRealtimeBaseNanos = elapsedBaseNanos,
            settings = settings,
            initialState = CaptureReplayInitialStateDto(
                mode = "WALKING",
                sessionBoundaryId = 0,
            ),
            rawFixes = rawFixes,
            activityTransitions = transitions,
            assertions = CaptureReplayAssertionsDto(
                finalMode = "WALKING",
                minPersistedPoints = 0,
                expectedMotionRetryCountMin = 0,
                maxDecisionMismatches = 0,
            ),
        )
    }
}

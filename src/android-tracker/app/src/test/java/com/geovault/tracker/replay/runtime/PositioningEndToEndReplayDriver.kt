package com.geovault.tracker.replay.runtime

import android.content.Context
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.positioning.PositioningAndroidPorts
import com.geovault.tracker.positioning.PositioningRuntime
import com.geovault.tracker.tracking.TrackingService
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric

internal class PositioningEndToEndReplayDriver(
    private val session: CaptureReplaySessionDto,
) {
    fun runReplay(): PositioningEndToEndReplayResult {
        TrackPointCrossSourceState.resetForTests()
        TrackPointPolicyEngine.resetAll()

        val clock = ReplayPositioningClock(
            wallTimeMs = session.wallBaseMs,
            elapsedRealtimeNanos = session.elapsedRealtimeBaseNanos,
        )
        val service = Robolectric.buildService(TrackingService::class.java).get()
        val appContext = service.applicationContext
        clearTelemetry(appContext)
        SelectedTrackerPrefs.setSelectedTracker(
            context = appContext,
            trackerId = session.trackId,
            trackerName = "Replay Tracker",
        )

        val environment = ReplayRuntimeEnvironment(clock, session.settings.toSettings())
        val runtime = PositioningRuntime(
            ports = PositioningAndroidPorts(service),
            environment = environment,
        )
        runtime.onCreate()

        runBlocking {
            runtime.lifecycle.startReplaySession(
                trigger = "capture_replay",
                startWallMs = session.wallBaseMs,
            )
            feedRawFixes(runtime, clock)
        }

        return PositioningEndToEndReplayResult(
            runtime = runtime,
            environment = environment,
            service = service,
        )
    }

    private suspend fun feedRawFixes(
        runtime: PositioningRuntime,
        clock: ReplayPositioningClock,
    ) {
        var previousWallMs = session.wallBaseMs
        for (fix in session.rawFixes) {
            val nextWallMs = fix.wallTimeMs(session)
            injectMotionTicks(runtime, clock, previousWallMs, nextWallMs)
            clock.advanceTo(
                wallTimeMs = nextWallMs,
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos(session),
            )
            runtime.fixIngest.processLocationUpdateSerialized(
                location = fix.toLocation(session),
                bypassFilters = fix.bypassFilters,
                allowWhenGpsPaused = fix.allowWhenGpsPaused,
                skipAdaptiveTrackingEffects = fix.skipAdaptiveTrackingEffects,
            )
            previousWallMs = nextWallMs
        }
    }

    private fun injectMotionTicks(
        runtime: PositioningRuntime,
        clock: ReplayPositioningClock,
        fromWallMs: Long,
        toWallMs: Long,
    ) {
        var tickMs = fromWallMs + TickIntervalMs
        while (tickMs < toWallMs) {
            clock.advanceTo(
                wallTimeMs = tickMs,
                elapsedRealtimeNanos = session.elapsedRealtimeBaseNanos + (tickMs - session.wallBaseMs) * 1_000_000L,
            )
            runtime.motion.processAutoModeTick(nowMs = tickMs)
            tickMs += TickIntervalMs
        }
    }

    private fun clearTelemetry(context: Context) {
        context.getSharedPreferences(TelemetryPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        private const val TickIntervalMs = 5_000L
        private const val TelemetryPrefsName = "tracking_runtime_telemetry_v2"
    }
}

internal class PositioningEndToEndReplayResult(
    val runtime: PositioningRuntime,
    private val environment: ReplayRuntimeEnvironment,
    private val service: TrackingService,
) : AutoCloseable {
    val persistedPointCount: Int
        get() = environment.replayDatabase.locationDao().getCurrentSessionCountForTracker(
            trackerId = runtime.ports.selectedTrackerId(),
            sessionBoundaryId = runtime.state.sessionVisibleBoundaryId,
        )

    val telemetryLines: List<String>
        get() = service.applicationContext
            .getSharedPreferences(TelemetryPrefsName, Context.MODE_PRIVATE)
            .getString(TelemetryKeyRing, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

    override fun close() {
        if (runtime.state.isTracking) {
            runtime.lifecycle.stopTracking(reason = "replay_complete")
        }
        runtime.onDestroy()
        environment.replayDatabase.close()
    }

    private companion object {
        private const val TelemetryPrefsName = "tracking_runtime_telemetry_v2"
        private const val TelemetryKeyRing = "ring"
    }
}

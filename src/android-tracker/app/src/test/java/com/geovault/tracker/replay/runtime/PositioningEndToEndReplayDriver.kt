package com.geovault.tracker.replay.runtime

import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.positioning.PositioningAndroidPorts
import com.geovault.tracker.positioning.PositioningRuntime
import com.geovault.tracker.runtime.RuntimeTelemetryStore
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.tracking.TrackingService
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric

internal class PositioningEndToEndReplayDriver(
    private val session: CaptureReplaySessionDto,
    /**
     * Actions to run against the live [PositioningRuntime] partway through the replay, each keyed
     * by a wall-offset (relative to [CaptureReplaySessionDto.wallBaseMs]) at which it should fire.
     * An action fires once, immediately after the replay clock has advanced to (or past) its
     * trigger offset but before that timeline event is ingested. This is the only way to reach
     * runtime-internal state (e.g. `runtime.deps.settingsRepository.setSparseTracking(true)`)
     * mid-session, since [PositioningRuntime] is only ever constructed here.
     */
    private val midReplayActions: List<Pair<Long, (PositioningRuntime) -> Unit>> = emptyList(),
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
        RuntimeTelemetryStore.deleteStore(appContext)
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
            applyInitialMode(runtime)
            feedMergedTimeline(runtime, clock)
        }

        return PositioningEndToEndReplayResult(
            runtime = runtime,
            environment = environment,
            service = service,
        )
    }

    private fun applyInitialMode(runtime: PositioningRuntime) {
        val mode = TrackingMotionMode.entries.firstOrNull {
            it.name == session.initialState.mode
        } ?: return
        if (mode != TrackingMotionMode.WALKING) {
            runtime.deps.autoTrackingMotionEngine.overrideInitialMode(mode)
        }
    }

    private suspend fun feedMergedTimeline(
        runtime: PositioningRuntime,
        clock: ReplayPositioningClock,
    ) {
        val timeline = buildMergedTimeline()
        var previousWallMs = session.wallBaseMs
        val pendingActions = midReplayActions.sortedBy { it.first }.toMutableList()
        for (event in timeline) {
            val wallMs = event.wallTimeMs(session)
            injectMotionTicks(runtime, clock, previousWallMs, wallMs)
            clock.advanceTo(
                wallTimeMs = wallMs,
                elapsedRealtimeNanos = event.elapsedRealtimeNanos(session),
            )
            firePendingActions(pendingActions, runtime, wallMs)
            when (event) {
                is TimelineEvent.Fix -> runtime.fixIngest.processLocationUpdateSerialized(
                    location = event.fix.toLocation(session),
                    bypassFilters = event.fix.bypassFilters,
                    allowWhenGpsPaused = event.fix.allowWhenGpsPaused,
                    skipAdaptiveTrackingEffects = event.fix.skipAdaptiveTrackingEffects,
                )
                is TimelineEvent.Imu -> runtime.motion.onImuMotionUpdate(event.imuEvent.toContext())
            }
            previousWallMs = wallMs
        }
        firePendingActions(pendingActions, runtime, wallMs = previousWallMs, drainAll = true)
    }

    private fun firePendingActions(
        pendingActions: MutableList<Pair<Long, (PositioningRuntime) -> Unit>>,
        runtime: PositioningRuntime,
        wallMs: Long,
        drainAll: Boolean = false,
    ) {
        while (pendingActions.isNotEmpty() &&
            (drainAll || session.wallBaseMs + pendingActions.first().first <= wallMs)
        ) {
            pendingActions.removeAt(0).second(runtime)
        }
    }

    private fun buildMergedTimeline(): List<TimelineEvent> {
        val fixes = session.rawFixes.map { TimelineEvent.Fix(it) }
        // IMU events with a negative wall offset predate the first GPS fix (wall base).
        // They cannot be replayed because the monotonic clock starts at wallBaseMs.
        val imuEvents = session.imuEvents
            .filter { it.wallOffsetMs >= 0L }
            .map { TimelineEvent.Imu(it) }
        return (fixes + imuEvents).sortedBy { it.wallTimeMs(session) }
    }

    private sealed class TimelineEvent {
        abstract fun wallTimeMs(session: CaptureReplaySessionDto): Long
        abstract fun elapsedRealtimeNanos(session: CaptureReplaySessionDto): Long

        data class Fix(val fix: CaptureReplayRawFixDto) : TimelineEvent() {
            override fun wallTimeMs(session: CaptureReplaySessionDto) = fix.wallTimeMs(session)
            override fun elapsedRealtimeNanos(session: CaptureReplaySessionDto) = fix.elapsedRealtimeNanos(session)
        }

        data class Imu(val imuEvent: CaptureReplayImuEventDto) : TimelineEvent() {
            override fun wallTimeMs(session: CaptureReplaySessionDto) = imuEvent.wallTimeMs(session)
            override fun elapsedRealtimeNanos(session: CaptureReplaySessionDto) = imuEvent.elapsedRealtimeNanos(session)
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

    private companion object {
        private const val TickIntervalMs = 5_000L
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
        get() = runtime.deps.runtimeTelemetry.readAllLines()

    override fun close() {
        if (runtime.state.isTracking) {
            runtime.lifecycle.stopTracking(reason = "replay_complete")
        }
        runtime.onDestroy()
        environment.replayDatabase.close()
    }
}

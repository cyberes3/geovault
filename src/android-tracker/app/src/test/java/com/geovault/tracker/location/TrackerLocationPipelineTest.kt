package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterReasons
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerLocationPipelineTest {

    @Before
    fun setUp() {
        TrackPointCrossSourceState.resetForTests()
        TrackPointPolicyEngine.resetAll()
    }

    @Test
    fun processFix_autoMotionModeChange_retriesIngestOnce() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val pipeline = TrackerLocationPipeline(
            locationIngestCoordinator = coordinator,
            freshnessRecoveryController = FreshnessRecoveryController(),
            repeatedOutlierSuppressor = RepeatedOutlierSuppressor(),
        )
        val settings = TrackerSettings(accuracyFilterMeters = 50f)
        val trackId = "tracker-1"
        val anchorTimeMs = 1_700_000_000_000L
        val seed = coordinator.ingest(
            trackId = trackId,
            location = Location("gps").apply {
                latitude = 12.0
                longitude = -45.0
                accuracy = 5f
                time = anchorTimeMs
            },
            settings = settings,
            motionMode = TrackingMotionMode.WALKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = trackId,
            nowMs = anchorTimeMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false,
        )
        assertTrue(seed.accepted)

        val nowMs = anchorTimeMs + 4 * 60_000L + 20_000L
        val candidate = Location("gps").apply {
            latitude = 12.0
            longitude = -44.996
            accuracy = 8f
            speed = 22f
            time = nowMs
        }
        var refreshCount = 0
        val output = pipeline.processFix(
            input = pipelineInput(
                trackId = trackId,
                location = candidate,
                settings = settings,
                previousAcceptedLocation = seed.lastFilteredLocation,
                nowMs = nowMs,
                motionMode = TrackingMotionMode.WALKING,
            ),
            onAutoMotionRejected = { _, _, _ ->
                AutoMotionRejectHandling.Evidence(
                    modeBefore = TrackingMotionMode.WALKING,
                    output = AutoTrackingEngineOutput(
                        state = AutoTrackingMotionState(mode = TrackingMotionMode.DRIVING),
                        modeChanged = true,
                        transitionPath = TransitionPath.LADDER,
                    ),
                    evidence = AutoTrackingMotionEvidence(
                        speedMps = 20f,
                        confidence = AutoTrackingMotionEvidenceConfidence.High,
                        path = EvidencePath.FAST_EMIT,
                    ),
                    policyReason = LocationFilterReasons.SPEED_CAP_EXCEEDED,
                    accuracyMeters = 8f,
                    elapsedSeconds = 20.0,
                )
            },
            refreshMotionContext = {
                refreshCount++
                motionContext(motionMode = TrackingMotionMode.DRIVING)
            },
            buildFreshnessRecoveryLocation = { recoveryAnchor, _, recoveryNowMs, recoveryNanos ->
                recoveryAnchor.toLocation(providerPrefix = "freshness_recovery").apply {
                    time = recoveryNowMs
                    elapsedRealtimeNanos = recoveryNanos
                }
            },
        )

        assertEquals(1, refreshCount)
        assertTrue(output.motionModeChanged)
        assertEquals(TrackingMotionMode.DRIVING, output.motionContext.motionMode)
    }

    private fun pipelineInput(
        trackId: String = "tracker-1",
        location: Location,
        settings: TrackerSettings = TrackerSettings(accuracyFilterMeters = 25f),
        previousAcceptedLocation: Location? = null,
        nowMs: Long,
        motionMode: TrackingMotionMode = TrackingMotionMode.BIKING,
        localRecoveryDue: Boolean = false,
        anchor: RecoveryAnchorState? = null,
        outlierAnchor: Location? = null,
    ): TrackerLocationPipelineInput {
        val motion = motionContext(motionMode = motionMode)
        return TrackerLocationPipelineInput(
            trackId = trackId,
            location = location,
            settings = settings,
            motionContext = motion,
            previousAcceptedLocation = previousAcceptedLocation,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            sessionStartTimeMs = 0L,
            isMockLocation = false,
            skipAdaptiveTrackingEffects = false,
            localRecoveryDue = localRecoveryDue,
            recoveryConfig = PositioningRecoveryConfig(
                maxLocalPointGapMs = 120_000L,
                recoverySpeedCapMps = 5f,
            ),
            recoveryAnchor = anchor,
            outlierSuppressorAnchor = outlierAnchor,
        )
    }

    private fun motionContext(
        motionMode: TrackingMotionMode = TrackingMotionMode.BIKING,
    ): TrackerLocationMotionContext {
        return TrackerLocationMotionContext(
            motionMode = motionMode,
            filterConfig = LocationFilterConfig.Default,
            effectiveAccuracyThresholdMeters = 25f,
        )
    }
}

private class FakeLocationDao : LocationDao {
    private val rows = mutableListOf<QueuedLocation>()
    private var nextId = 1L

    override fun insert(location: QueuedLocation): Long {
        val stored = location.copy(id = nextId++)
        rows.add(stored)
        return stored.id
    }

    override fun insertAll(locations: List<QueuedLocation>) {
        locations.forEach { insert(it) }
    }

    override fun getAll(): List<QueuedLocation> = rows.sortedBy { it.time }

    override fun getRecentChronological(limit: Int): List<QueuedLocation> =
        rows.sortedByDescending { it.time }.take(limit).reversed()

    override fun getRecentChronologicalForTracker(trackerId: String, limit: Int): List<QueuedLocation> =
        rows.filter { it.trackerId == trackerId }.sortedByDescending { it.time }.take(limit).reversed()

    override fun getOldestForTracker(trackerId: String, limit: Int): List<QueuedLocation> =
        rows.filter { it.trackerId == trackerId }.sortedBy { it.id }.take(limit)

    override fun getOldestBacklogForTracker(trackerId: String, sessionBoundaryId: Long, limit: Int): List<QueuedLocation> =
        rows.filter { it.trackerId == trackerId && it.id <= sessionBoundaryId }.sortedBy { it.id }.take(limit)

    override fun getOldestCurrentSessionForTracker(trackerId: String, sessionBoundaryId: Long, limit: Int): List<QueuedLocation> =
        rows.filter { it.trackerId == trackerId && it.id > sessionBoundaryId }.sortedBy { it.id }.take(limit)

    override fun delete(locations: List<QueuedLocation>) {
        val ids = locations.map { it.id }.toSet()
        rows.removeAll { it.id in ids }
    }

    override fun getCount(): Int = rows.size

    override fun getCountForTracker(trackerId: String): Int = rows.count { it.trackerId == trackerId }

    override fun getMaxId(): Long = rows.maxOfOrNull { it.id } ?: 0L

    override fun getCurrentSessionCountById(sessionBoundaryId: Long): Int =
        rows.count { it.id > sessionBoundaryId }

    override fun getCurrentSessionCountForTracker(trackerId: String, sessionBoundaryId: Long): Int =
        rows.count { it.trackerId == trackerId && it.id > sessionBoundaryId }

    override fun getBacklogCountById(sessionBoundaryId: Long): Int = rows.count { it.id <= sessionBoundaryId }

    override fun getBacklogCountForTracker(trackerId: String, sessionBoundaryId: Long): Int =
        rows.count { it.trackerId == trackerId && it.id <= sessionBoundaryId }

    override fun deleteOlderThan(cutoffTimeMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.time < cutoffTimeMs }
        return before - rows.size
    }

    override fun deleteOlderThanForTracker(trackerId: String, cutoffTimeMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.trackerId == trackerId && it.time < cutoffTimeMs }
        return before - rows.size
    }

    override fun deleteOldestCount(count: Int): Int {
        if (count <= 0) return 0
        val oldest = rows.sortedBy { it.time }.take(count).map { it.id }.toSet()
        val before = rows.size
        rows.removeAll { it.id in oldest }
        return before - rows.size
    }

    override fun deleteOldestCountForTracker(trackerId: String, count: Int): Int {
        if (count <= 0) return 0
        val oldest = rows.filter { it.trackerId == trackerId }.sortedBy { it.time }.take(count).map { it.id }.toSet()
        val before = rows.size
        rows.removeAll { it.id in oldest }
        return before - rows.size
    }

    override fun updateDistanceById(id: Long, distanceMeters: Float) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(dist = distanceMeters)
        }
    }

    override fun deleteAll() {
        rows.clear()
    }
}

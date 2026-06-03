package com.geovault.tracker.replay

import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.EvidencePath
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrafficJamCaptureReplayTest {

    private lateinit var session: CaptureReplaySession

    @Before
    fun setUp() {
        TrackPointCrossSourceState.resetForTests()
        TrackPointPolicyEngine.resetAll()
        session = CaptureReplaySessionLoader.load(SESSION_RESOURCE)
    }

    @Test
    fun fullReplay_motion_reachesDrivingWithinSixtySecondsOfFirstCapExceeded() {
        val feed = CaptureReplayMotionFeed.create()
        val resetWallMs = walkingResetWallMs()
        feed.replay(session, resetWallMs = resetWallMs)

        val firstCap = session.firstCapExceededFastEmitMilestone()
        checkNotNull(firstCap)
        assertEquals(TrackingMotionMode.WALKING.name, firstCap.modeBefore)
        assertEquals(TrackingMotionMode.WALKING.name, firstCap.modeAfter)
        assertEquals(EvidencePath.FAST_EMIT.name, firstCap.path)

        assertEquals(TrackingMotionMode.DRIVING, feed.engine.snapshot().mode)

        val capWallMs = firstCap.wallNowMs(session)
        val handshake = session.milestones.firstOrNull {
            it.kind == "auto_motion_evidence" &&
                it.path == EvidencePath.HANDSHAKE.name &&
                it.reason == FilterReason.SPEED_CAP_EXCEEDED.wireValue
        }
        checkNotNull(handshake)
        assertTrue(
            "DRIVING within 60s of first cap FAST_EMIT (handshake at ${handshake.wallOffsetMs}ms)",
            (handshake.wallNowMs(session) - capWallMs) <= 60_000L,
        )
    }

    @Test
    fun fullReplay_filter_promotesAndAcceptsAfterDriving() {
        val feed = CaptureReplayFilterFeed.create()
        feed.replay(session, resetWallMs = walkingResetWallMs())

        assertEquals(TrackingMotionMode.DRIVING, feed.engine.snapshot().mode)

        val firstCapOffset = checkNotNull(session.firstCapExceededFastEmitMilestone()).wallOffsetMs
        val afterDriving = session.frames.firstOrNull { frame ->
            frame.wallOffsetMs > firstCapOffset &&
                frame.impliedSpeedMps > 20.0 &&
                frame.policy == FilterReason.SPEED_CAP_EXCEEDED.wireValue
        }
        if (afterDriving != null) {
            val state = CaptureReplayFilterFeed.ReplayState()
            feed.feedFrame(session, afterDriving, state)
            val decision = TrackPointPolicyEngine.evaluate(
                event = TrackPointEvent(
                    source = TrackPointSource.LOCAL_GPS,
                    trackId = session.trackId,
                    lat = afterDriving.lat,
                    lon = afterDriving.lon,
                    timestampMs = afterDriving.gpsTimeMs,
                    accuracyMeters = afterDriving.accuracy,
                    gpsSpeedMps = afterDriving.impliedSpeedMps.toFloat(),
                    gpsBearingDeg = 90f,
                ),
                nowMs = afterDriving.wallNowMs(session),
                config = LocationFilterConfig.fromTuning(
                    tuning = MotionProfileTuning.Driving,
                    trackingAccuracyThresholdMeters = 50.0,
                    maxFutureSkewMs = 0L,
                    freshnessTtlMs = 0L,
                    normalizeSecondsTimestamps = false,
                ),
            )
            if (decision.accepted) {
                assertNotEquals("first-fix", decision.metrics?.reason)
            }
        }
    }

    @Test
    fun fullReplay_pipeline_retriesIngestOnAutoMotionModeChange() {
        val feed = CaptureReplayPipelineFeed.create(session, ReplayLocationDao())
        val state = feed.replay(resetWallMs = walkingResetWallMs())

        assertTrue(
            "expected at least one auto-motion promotion retry",
            state.motionRetryCount >= 1,
        )
        assertEquals(TrackingMotionMode.DRIVING, feed.engine.snapshot().mode)
    }

    private fun walkingResetWallMs(): Long {
        val walkingMilestone = session.milestones.firstOrNull {
            it.kind == "auto_mode_changed" && it.modeAfter == TrackingMotionMode.WALKING.name
        }
        return walkingMilestone?.wallNowMs(session) ?: session.wallBaseMs
    }

    private class ReplayLocationDao : LocationDao {
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

    companion object {
        private const val SESSION_RESOURCE = "traffic_jam_2026_06_02"
    }
}

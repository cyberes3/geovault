package com.geovault.tracker.services

import android.location.Location
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationIngestCoordinatorTest {

    @Before
    fun setUp() {
        TrackPointCrossSourceState.resetForTests()
        TrackPointPolicyEngine.resetAll()
    }

    @Test
    fun ingest_manualBypass_acceptsLocation_andMarksProps() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(
            distanceFilterMeters = 1000f,
            accuracyFilterMeters = 5f
        )
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 500f
            time = 12345L
        }

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = location,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = """{"manual_send":true}""",
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = System.currentTimeMillis(),
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(result.accepted)
        assertEquals(1, dao.getCount())
        assertEquals("tracker-1", dao.getAll().single().trackerId)
        assertEquals(1, result.queuedPointsVisible)
        assertEquals("""{"manual_send":true}""", result.lastTrackedPropsJson)
    }

    @Test
    fun ingest_manualBypass_updatesPolicyAnchorForNextFix() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()

        val manualBypass = Location("manual_send:fused").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs
        }
        val olderFix = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs - 5_000L
        }

        val bypassResult = coordinator.ingest(
            trackId = "tracker-1",
            location = manualBypass,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = """{"manual_send":true}""",
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )
        val secondResult = coordinator.ingest(
            trackId = "tracker-1",
            location = olderFix,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(bypassResult.accepted)
        assertFalse(secondResult.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, secondResult.rejectReason)
    }

    @Test
    fun ingest_manualBypass_rejectsOutOfOrderAgainstAcceptedState() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()
        val first = Location("manual_send:fused").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs
        }
        val older = Location("manual_send:fused").apply {
            latitude = 10.0
            longitude = 20.0001
            accuracy = 5f
            time = nowMs - 1_000L
        }

        val firstResult = coordinator.ingest(
            trackId = "tracker-1",
            location = first,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )
        val olderResult = coordinator.ingest(
            trackId = "tracker-1",
            location = older,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(firstResult.accepted)
        assertFalse(olderResult.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, olderResult.rejectReason)
        assertEquals(1, dao.getCount())
    }


    @Test
    fun ingest_withoutBypass_rejectsLowAccuracy() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 10f)
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 500f
            time = 12345L
        }

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = location,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = System.currentTimeMillis(),
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertFalse(result.accepted)
        assertEquals(0, dao.getCount())
        assertNotNull(result.lastAccuracyMeters)
    }

    @Test
    fun ingest_withoutBypass_rejectsStaleFix() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs - 180_000L
        }

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = location,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertFalse(result.accepted)
        assertEquals(TrackPointRejectReason.STALE, result.rejectReason)
    }

    @Test
    fun ingest_mockFix_withLargeTimestampSkew_isNormalizedAndAccepted() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs - (20 * 60 * 1000L)
        }

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = location,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = true
        )

        assertTrue(result.accepted)
        assertEquals(1, dao.getCount())
    }

    @Test
    fun ingest_acceptsFix_persistsDistanceAtomicallyOnInsert() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()
        val previous = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = nowMs - 1000L
        }
        val location = Location("gps").apply {
            latitude = 10.001
            longitude = 20.001
            accuracy = 5f
            time = nowMs
        }
        val startingDistance = 123f

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = location,
            settings = settings,
            motionMode = TrackingMotionMode.BIKING,
            previousAcceptedLocation = previous,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = null,
            totalDistanceMeters = startingDistance,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(result.accepted)
        assertTrue(result.pointPersisted)
        assertTrue(result.nextSessionDistanceMeters > startingDistance)
        assertEquals(result.nextSessionDistanceMeters, dao.getAll().single().dist)
    }

    @Test
    fun ingest_pausedFreshnessBypassAtAnchor_persistsWithoutAddingDistance() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val nowMs = System.currentTimeMillis()
        val anchor = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 6f
            time = nowMs - 5 * 60_000L
        }
        val probe = Location("gps").apply {
            latitude = 10.0
            longitude = 20.00001
            accuracy = 8f
            time = nowMs
        }
        val freshness = PausedFreshnessPointFactory.buildAnchoredFreshnessLocation(
            anchorLocation = anchor,
            probeLocation = probe,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 123L,
        )
        val startingDistance = 456f

        val result = coordinator.ingest(
            trackId = "tracker-1",
            location = freshness,
            settings = settings,
            motionMode = TrackingMotionMode.WALKING,
            previousAcceptedLocation = anchor,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = null,
            totalDistanceMeters = startingDistance,
            queuedTrackerId = "tracker-1",
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 123L,
            isMockLocation = false
        )

        assertTrue(result.accepted)
        assertTrue(result.pointPersisted)
        assertEquals(startingDistance, result.nextSessionDistanceMeters, 0.001f)
        val row = dao.getAll().single()
        assertEquals(startingDistance, row.dist ?: -1f, 0.001f)
        assertEquals(anchor.latitude, row.latitude, 0.0)
        assertEquals(anchor.longitude, row.longitude, 0.0)
        assertEquals("paused_freshness:gps", row.prov)
    }

    @Test
    fun ingest_resumeUnconfirmedRejects_doNotForceLocalReanchor() {
        val dao = FakeLocationDao()
        val coordinator = LocationIngestCoordinator(dao)
        val settings = TrackerSettings(accuracyFilterMeters = 25f)
        val trackId = "tracker-1"
        val anchorTimeMs = 1_700_000_000_000L
        val anchor = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            accuracy = 5f
            time = anchorTimeMs
        }
        val seed = coordinator.ingest(
            trackId = trackId,
            location = anchor,
            settings = settings,
            motionMode = TrackingMotionMode.DRIVING,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = trackId,
            nowMs = anchorTimeMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )
        assertTrue(seed.accepted)

        TrackPointPolicyEngine.notifyMotionChanged(TrackPointSource.LOCAL_GPS, trackId)

        var previousAccepted = seed.lastFilteredLocation
        var lastResult: LocationIngestResult? = null
        repeat(TrackingPolicyProfiles.LOCAL_STALL_REJECT_STREAK_THRESHOLD.toInt()) { idx ->
            val nowMs = anchorTimeMs + 4 * 60_000L + idx * 1_000L
            val candidate = Location("gps").apply {
                latitude = 10.010 + idx * 0.002
                longitude = 20.0
                accuracy = 5f
                time = nowMs
            }
            val result = coordinator.ingest(
                trackId = trackId,
                location = candidate,
                settings = settings,
                motionMode = TrackingMotionMode.DRIVING,
                previousAcceptedLocation = previousAccepted,
                sessionVisibleBoundaryId = 0L,
                bypassFilters = false,
                propsJson = null,
                totalDistanceMeters = 0f,
                queuedTrackerId = trackId,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = idx.toLong() * 1_000_000_000L,
                isMockLocation = false
            )
            lastResult = result
            previousAccepted = result.lastFilteredLocation
        }

        val result = checkNotNull(lastResult)
        assertFalse(result.accepted)
        assertEquals(TrackPointRejectReason.JUMP, result.rejectReason)
        assertEquals("resume-unconfirmed", result.policyMetrics?.reason)
        assertEquals(1, dao.getCount())
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

    override fun getRecentChronological(limit: Int): List<QueuedLocation> {
        return rows.sortedByDescending { it.time }.take(limit).reversed()
    }

    override fun getRecentChronologicalForTracker(trackerId: String, limit: Int): List<QueuedLocation> {
        return rows.filter { it.trackerId == trackerId }.sortedByDescending { it.time }.take(limit).reversed()
    }

    override fun getOldestForTracker(trackerId: String, limit: Int): List<QueuedLocation> {
        return rows.filter { it.trackerId == trackerId }.sortedBy { it.id }.take(limit)
    }

    override fun getOldestBacklogForTracker(
        trackerId: String,
        sessionBoundaryId: Long,
        limit: Int,
    ): List<QueuedLocation> {
        return rows.filter { it.trackerId == trackerId && it.id <= sessionBoundaryId }
            .sortedBy { it.id }
            .take(limit)
    }

    override fun getOldestCurrentSessionForTracker(
        trackerId: String,
        sessionBoundaryId: Long,
        limit: Int,
    ): List<QueuedLocation> {
        return rows.filter { it.trackerId == trackerId && it.id > sessionBoundaryId }
            .sortedBy { it.id }
            .take(limit)
    }

    override fun delete(locations: List<QueuedLocation>) {
        val ids = locations.map { it.id }.toSet()
        rows.removeAll { it.id in ids }
    }

    override fun getCount(): Int = rows.size

    override fun getCountForTracker(trackerId: String): Int {
        return rows.count { it.trackerId == trackerId }
    }

    override fun getMaxId(): Long = rows.maxOfOrNull { it.id } ?: 0L

    override fun getCurrentSessionCountById(sessionBoundaryId: Long): Int {
        return rows.count { it.id > sessionBoundaryId }
    }

    override fun getCurrentSessionCountForTracker(trackerId: String, sessionBoundaryId: Long): Int {
        return rows.count { it.trackerId == trackerId && it.id > sessionBoundaryId }
    }

    override fun getBacklogCountById(sessionBoundaryId: Long): Int {
        return rows.count { it.id <= sessionBoundaryId }
    }

    override fun getBacklogCountForTracker(trackerId: String, sessionBoundaryId: Long): Int {
        return rows.count { it.trackerId == trackerId && it.id <= sessionBoundaryId }
    }

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
        val oldest = rows
            .filter { it.trackerId == trackerId }
            .sortedBy { it.time }
            .take(count)
            .map { it.id }
            .toSet()
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

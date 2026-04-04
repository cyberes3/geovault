package com.geovault.tracker.services

import android.location.Location
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointRejectReason
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
            nowMs = System.currentTimeMillis(),
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(result.accepted)
        assertEquals(1, dao.getCount())
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
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(bypassResult.accepted)
        assertFalse(secondResult.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, secondResult.rejectReason)
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
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false
        )

        assertTrue(result.accepted)
        assertTrue(result.pointPersisted)
        assertTrue(result.nextSessionDistanceMeters > startingDistance)
        assertEquals(result.nextSessionDistanceMeters, dao.getAll().single().dist)
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

    override fun getOldest(limit: Int): List<QueuedLocation> = rows.sortedBy { it.time }.take(limit)

    override fun getOldestBacklogById(sessionBoundaryId: Long, limit: Int): List<QueuedLocation> {
        return rows.filter { it.id <= sessionBoundaryId }.sortedBy { it.id }.take(limit)
    }

    override fun getOldestCurrentSessionById(sessionBoundaryId: Long, limit: Int): List<QueuedLocation> {
        return rows.filter { it.id > sessionBoundaryId }.sortedBy { it.id }.take(limit)
    }

    override fun delete(locations: List<QueuedLocation>) {
        val ids = locations.map { it.id }.toSet()
        rows.removeAll { it.id in ids }
    }

    override fun getCount(): Int = rows.size

    override fun getMaxId(): Long = rows.maxOfOrNull { it.id } ?: 0L

    override fun getCurrentSessionCountById(sessionBoundaryId: Long): Int {
        return rows.count { it.id > sessionBoundaryId }
    }

    override fun getBacklogCountById(sessionBoundaryId: Long): Int {
        return rows.count { it.id <= sessionBoundaryId }
    }

    override fun deleteOlderThan(cutoffTimeMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.time < cutoffTimeMs }
        return before - rows.size
    }

    override fun deleteOldestCount(count: Int): Int {
        if (count <= 0) return 0
        val oldest = rows.sortedBy { it.time }.take(count).map { it.id }.toSet()
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

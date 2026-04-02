package com.geovault.tracker.services

import android.location.Location
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationIngestCoordinatorTest {

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
            location = location,
            settings = settings,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            maxQueueSize = 5000,
            bypassFilters = true,
            propsJson = """{"manual_send":true}""",
            totalDistanceMeters = 0f
        )

        assertTrue(result.accepted)
        assertEquals(1, dao.getCount())
        assertEquals(1, result.queuedPointsVisible)
        assertEquals("""{"manual_send":true}""", result.lastTrackedPropsJson)
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
            location = location,
            settings = settings,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            maxQueueSize = 5000,
            bypassFilters = false,
            propsJson = null,
            totalDistanceMeters = 0f
        )

        assertFalse(result.accepted)
        assertEquals(0, dao.getCount())
        assertNotNull(result.lastAccuracyMeters)
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

    override fun deleteAll() {
        rows.clear()
    }
}

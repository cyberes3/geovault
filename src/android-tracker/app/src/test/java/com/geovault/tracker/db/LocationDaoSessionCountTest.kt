package com.geovault.tracker.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationDaoSessionCountTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LocationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
        dao = db.locationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getCurrentSessionCountById_excludesRowsBeforeSessionBoundaryId() {
        dao.insert(queued(time = 5_000L))
        val boundaryId = dao.insert(queued(time = 1_000L))
        dao.insert(queued(time = 100L))
        dao.insert(queued(time = 200L))

        assertEquals(2, dao.getCurrentSessionCountById(sessionBoundaryId = boundaryId))
        assertEquals(2, dao.getBacklogCountById(sessionBoundaryId = boundaryId))
    }

    @Test
    fun getOldestCurrentSessionById_usesIdBoundaryNotPointTime() {
        val backlogA = dao.insert(queued(time = 10_000L))
        val boundaryId = dao.insert(queued(time = 20_000L))
        val liveA = dao.insert(queued(time = 100L))
        val liveB = dao.insert(queued(time = 200L))

        val liveRows = dao.getOldestCurrentSessionById(sessionBoundaryId = boundaryId, limit = 10)
        val backlogRows = dao.getOldestBacklogById(sessionBoundaryId = boundaryId, limit = 10)

        assertEquals(listOf(liveA, liveB), liveRows.map { it.id })
        assertEquals(listOf(backlogA, boundaryId), backlogRows.map { it.id })
    }

    private fun queued(time: Long): QueuedLocation {
        return QueuedLocation(
            time = time,
            latitude = 42.0,
            longitude = -71.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "gps",
            dist = null
        )
    }
}

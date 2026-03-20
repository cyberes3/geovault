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
    fun getCurrentSessionCount_excludesBacklogRows() {
        dao.insertAll(
            listOf(
                queued(time = 900L),
                queued(time = 999L),
                queued(time = 1000L),
                queued(time = 1200L)
            )
        )

        assertEquals(2, dao.getCurrentSessionCount(sessionBoundaryMs = 1000L))
        assertEquals(2, dao.getBacklogCount(sessionBoundaryMs = 1000L))
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

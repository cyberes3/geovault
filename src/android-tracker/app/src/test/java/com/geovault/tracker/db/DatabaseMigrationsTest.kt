package com.geovault.tracker.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [DatabaseMigrations.migration3To4] against a real on-disk SQLite instance (via
 * Robolectric). This verifies the backfill / drop / NOT NULL invariants without booting Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseMigrationsTest {

    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(V3SchemaCallback)
                .build()
        )
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate_withAttributableRows_preservesThem() {
        val db = helper.writableDatabase
        insertRow(db, trackerId = "tracker-a", time = 100L)
        insertRow(db, trackerId = "tracker-b", time = 200L)

        DatabaseMigrations.migration3To4(selectedTrackerId = null).migrate(db)

        val rows = readAllRows(db)
        assertEquals(2, rows.size)
        assertEquals(setOf("tracker-a", "tracker-b"), rows.map { it.trackerId }.toSet())
        assertTrue(hasTrackerIdIndex(db))
        assertNotNull("column should be NOT NULL", runCatching {
            db.execSQL("INSERT INTO queued_locations (tracker_id, time, latitude, longitude) VALUES (NULL, 0, 0, 0)")
        }.exceptionOrNull())
    }

    @Test
    fun migrate_backfillsNullTrackerFromSelectedTrackerId() {
        val db = helper.writableDatabase
        insertRow(db, trackerId = null, time = 100L)
        insertRow(db, trackerId = "   ", time = 150L)
        insertRow(db, trackerId = "tracker-a", time = 200L)

        DatabaseMigrations.migration3To4(selectedTrackerId = "tracker-selected").migrate(db)

        val rows = readAllRows(db)
        assertEquals(3, rows.size)
        val times = rows.associateBy { it.time }
        assertEquals("tracker-selected", times[100L]?.trackerId)
        assertEquals("tracker-selected", times[150L]?.trackerId)
        assertEquals("tracker-a", times[200L]?.trackerId)
    }

    @Test
    fun migrate_dropsUnattributableRowsWhenNoSelectedTracker() {
        val db = helper.writableDatabase
        insertRow(db, trackerId = null, time = 100L)
        insertRow(db, trackerId = "", time = 150L)
        insertRow(db, trackerId = "tracker-a", time = 200L)

        DatabaseMigrations.migration3To4(selectedTrackerId = null).migrate(db)

        val rows = readAllRows(db)
        assertEquals(1, rows.size)
        assertEquals("tracker-a", rows.single().trackerId)
        assertEquals(200L, rows.single().time)
    }

    private fun insertRow(db: SupportSQLiteDatabase, trackerId: String?, time: Long) {
        if (trackerId == null) {
            db.execSQL(
                "INSERT INTO queued_locations (tracker_id, time, latitude, longitude) VALUES (NULL, ?, 0, 0)",
                arrayOf<Any>(time),
            )
        } else {
            db.execSQL(
                "INSERT INTO queued_locations (tracker_id, time, latitude, longitude) VALUES (?, ?, 0, 0)",
                arrayOf<Any>(trackerId, time),
            )
        }
    }

    private data class Row(val id: Long, val trackerId: String?, val time: Long)

    private fun readAllRows(db: SupportSQLiteDatabase): List<Row> {
        db.query("SELECT id, tracker_id, time FROM queued_locations ORDER BY id ASC").use { cursor ->
            val out = mutableListOf<Row>()
            while (cursor.moveToNext()) {
                out.add(
                    Row(
                        id = cursor.getLong(0),
                        trackerId = if (cursor.isNull(1)) null else cursor.getString(1),
                        time = cursor.getLong(2),
                    )
                )
            }
            return out
        }
    }

    private fun hasTrackerIdIndex(db: SupportSQLiteDatabase): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='queued_locations' " +
                "AND name='index_queued_locations_tracker_id'",
        ).use { return it.moveToFirst() }
    }

    private companion object {
        const val DB_NAME = "migrations_test.db"

        val V3SchemaCallback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queued_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tracker_id TEXT,
                        time INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        altitude REAL,
                        speed REAL,
                        bearing REAL,
                        accuracy REAL,
                        sat INTEGER,
                        prov TEXT,
                        dist REAL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    }
}

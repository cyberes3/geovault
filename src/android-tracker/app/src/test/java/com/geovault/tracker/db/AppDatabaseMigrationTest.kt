package com.geovault.tracker.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppDatabaseMigrationTest {

    @Test
    fun migrateFrom1To2_preservesQueuedLocations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "tracker_migration_${System.currentTimeMillis()}"

        val sqliteDb = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queued_locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
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
        sqliteDb.execSQL(
            "INSERT INTO queued_locations (time, latitude, longitude, altitude, speed, bearing, accuracy, sat, prov, dist) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf(1000L, 42.0, -71.0, 10.0, 2.5f, 90.0f, 4.0f, 6, "gps", 12.0f)
        )
        sqliteDb.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
        try {
            assertEquals(1, roomDb.locationDao().getCount())
            val row = roomDb.locationDao().getAll().first()
            assertEquals(42.0, row.latitude, 0.0001)
            assertEquals(-71.0, row.longitude, 0.0001)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }
}

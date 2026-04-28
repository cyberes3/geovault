package com.geovault.tracker.db

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    private const val TAG = "DatabaseMigrations"

    /**
     * Version 2 introduces schema export and a formal migration path.
     * Schema stays unchanged; the migration is intentionally no-op.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) = Unit
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queued_locations ADD COLUMN tracker_id TEXT")
        }
    }

    /**
     * Version 4 promotes `tracker_id` to `NOT NULL`. Rows inserted before the
     * column existed (or while the producer still allowed blank ids) cannot be
     * attributed safely, even when a tracker is currently selected, so they are
     * dropped instead of being silently uploaded to the wrong tracker.
     */
    fun migration3To4(selectedTrackerId: String?): Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val beforeCount = singleLongQuery(db, "SELECT COUNT(*) FROM queued_locations")
            val attributableBefore = singleLongQuery(
                db,
                "SELECT COUNT(*) FROM queued_locations WHERE tracker_id IS NOT NULL AND TRIM(tracker_id) <> ''"
            )
            db.execSQL(
                """
                CREATE TABLE queued_locations_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    tracker_id TEXT NOT NULL,
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
            db.execSQL(
                """
                INSERT INTO queued_locations_new (id, tracker_id, time, latitude, longitude,
                    altitude, speed, bearing, accuracy, sat, prov, dist)
                SELECT id, TRIM(tracker_id), time, latitude, longitude,
                    altitude, speed, bearing, accuracy, sat, prov, dist
                FROM queued_locations
                WHERE tracker_id IS NOT NULL AND TRIM(tracker_id) <> ''
                """.trimIndent()
            )
            db.execSQL("DROP TABLE queued_locations")
            db.execSQL("ALTER TABLE queued_locations_new RENAME TO queued_locations")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_queued_locations_tracker_id ON queued_locations(tracker_id)"
            )
            val dropped = beforeCount - attributableBefore
            if (dropped > 0) {
                Log.i(
                    TAG,
                    "MIGRATION_3_4 dropped $dropped unattributable queued_locations row(s); " +
                        "selectedTrackerId=${if (selectedTrackerId.isNullOrBlank()) "absent" else "present"}"
                )
            }
        }
    }

    fun all(selectedTrackerId: String?): Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        migration3To4(selectedTrackerId)
    )

    private fun singleLongQuery(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }
}

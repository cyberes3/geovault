package com.geovault.tracker.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    /**
     * Version 2 introduces schema export and a formal migration path.
     * Schema stays unchanged; the migration is intentionally no-op.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) = Unit
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}

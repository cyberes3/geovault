package com.geovault.tracker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.geovault.tracker.SelectedTrackerPrefs

@Database(entities = [QueuedLocation::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val appContext = context.applicationContext
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(appContext)
                .takeIf { it.isNotBlank() }
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "tracker_database"
            ).addMigrations(*DatabaseMigrations.all(selectedTrackerId)).build()
        }
    }
}

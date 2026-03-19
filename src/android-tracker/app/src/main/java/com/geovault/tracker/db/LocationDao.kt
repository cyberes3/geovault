package com.geovault.tracker.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete

@Dao
interface LocationDao {
    @Insert
    fun insert(location: QueuedLocation): Long

    @Insert
    fun insertAll(locations: List<QueuedLocation>)

    @Query("SELECT * FROM queued_locations ORDER BY time ASC")
    fun getAll(): List<QueuedLocation>

    @Query("SELECT * FROM queued_locations ORDER BY time ASC LIMIT :limit")
    fun getOldest(limit: Int): List<QueuedLocation>

    @Delete
    fun delete(locations: List<QueuedLocation>)

    @Query("SELECT COUNT(*) FROM queued_locations")
    fun getCount(): Int

    @Query("DELETE FROM queued_locations WHERE time < :cutoffTimeMs")
    fun deleteOlderThan(cutoffTimeMs: Long): Int

    @Query("DELETE FROM queued_locations WHERE id IN (SELECT id FROM queued_locations ORDER BY time ASC LIMIT :count)")
    fun deleteOldestCount(count: Int): Int
    
    @Query("DELETE FROM queued_locations")
    fun deleteAll()
}

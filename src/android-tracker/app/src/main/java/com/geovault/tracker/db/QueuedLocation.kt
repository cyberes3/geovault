package com.geovault.tracker.db

import android.location.Location
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queued_locations",
    indices = [Index(value = ["tracker_id"], name = "index_queued_locations_tracker_id")]
)
data class QueuedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tracker_id") val trackerId: String,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val speed: Float?,
    val bearing: Float?,
    val accuracy: Float?,
    val sat: Int? = null,
    val prov: String? = null,
    val dist: Float? = null
) {
    init {
        require(trackerId.isNotBlank()) { "QueuedLocation.trackerId must not be blank" }
    }

    fun toLocation(): Location {
        val loc = Location(prov ?: "geovault")
        loc.time = time
        loc.latitude = latitude
        loc.longitude = longitude
        if (altitude != null) loc.altitude = altitude
        if (speed != null) loc.speed = speed
        if (bearing != null) loc.bearing = bearing
        if (accuracy != null) loc.accuracy = accuracy
        return loc
    }

    companion object {
        private const val EXTRAS_KEY_SATELLITES = "satellites"

        fun fromLocation(
            loc: Location,
            trackerId: String,
            totalDistanceMeters: Float? = null,
        ): QueuedLocation {
            require(trackerId.isNotBlank()) { "trackerId must not be blank" }
            val sat = loc.extras?.getInt(EXTRAS_KEY_SATELLITES, 0)?.takeIf { it > 0 } ?: 0
            return QueuedLocation(
                trackerId = trackerId,
                time = loc.time,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                sat = if (sat > 0) sat else null,
                prov = loc.provider,
                dist = totalDistanceMeters
            )
        }
    }
}

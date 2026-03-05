package com.geovault.tracker.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.location.Location

@Entity(tableName = "queued_locations")
data class QueuedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val speed: Float?,
    val bearing: Float?,
    val accuracy: Float?
) {
    fun toLocation(): Location {
        val loc = Location("geovault")
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
        fun fromLocation(loc: Location): QueuedLocation {
            return QueuedLocation(
                time = loc.time,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null
            )
        }
    }
}

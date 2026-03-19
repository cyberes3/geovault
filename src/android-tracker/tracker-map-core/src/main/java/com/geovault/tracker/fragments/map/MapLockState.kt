package com.geovault.tracker.fragments.map

import org.maplibre.android.geometry.LatLng

enum class MapLockMode {
    NONE,
    TRACKER_FOLLOW,
    GPS_FOLLOW,
    LIVE_FIT
}

sealed interface MapLockState {
    val mode: MapLockMode

    data object None : MapLockState {
        override val mode: MapLockMode = MapLockMode.NONE
    }

    data class TrackerFollow(
        val target: LatLng,
        val needsInitialZoom: Boolean
    ) : MapLockState {
        override val mode: MapLockMode = MapLockMode.TRACKER_FOLLOW
    }

    data object GpsFollow : MapLockState {
        override val mode: MapLockMode = MapLockMode.GPS_FOLLOW
    }

    data object LiveFit : MapLockState {
        override val mode: MapLockMode = MapLockMode.LIVE_FIT
    }
}

data class PersistedMapLockState(
    val mode: MapLockMode = MapLockMode.NONE,
    val targetLat: Double? = null,
    val targetLon: Double? = null,
    val needsInitialZoom: Boolean = false
)

object MapLockStateCodec {
    fun toPersisted(state: MapLockState): PersistedMapLockState {
        return when (state) {
            MapLockState.None -> PersistedMapLockState(mode = MapLockMode.NONE)
            is MapLockState.TrackerFollow -> PersistedMapLockState(
                mode = MapLockMode.TRACKER_FOLLOW,
                targetLat = state.target.latitude,
                targetLon = state.target.longitude,
                needsInitialZoom = state.needsInitialZoom
            )
            MapLockState.GpsFollow -> PersistedMapLockState(mode = MapLockMode.GPS_FOLLOW)
            MapLockState.LiveFit -> PersistedMapLockState(mode = MapLockMode.LIVE_FIT)
        }
    }

    fun fromPersisted(persisted: PersistedMapLockState): MapLockState {
        return when (persisted.mode) {
            MapLockMode.NONE -> MapLockState.None
            MapLockMode.TRACKER_FOLLOW -> {
                val lat = persisted.targetLat
                val lon = persisted.targetLon
                if (lat == null || lon == null) {
                    MapLockState.None
                } else {
                    MapLockState.TrackerFollow(
                        target = LatLng(lat, lon),
                        needsInitialZoom = persisted.needsInitialZoom
                    )
                }
            }
            MapLockMode.GPS_FOLLOW -> MapLockState.GpsFollow
            MapLockMode.LIVE_FIT -> MapLockState.LiveFit
        }
    }
}


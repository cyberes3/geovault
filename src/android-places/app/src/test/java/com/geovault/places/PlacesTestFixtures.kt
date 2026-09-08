package com.geovault.places

import com.geovault.common.geo.LonLat
import com.geovault.places.data.PlacesMutableStore
import com.geovault.places.data.PlacesRecords
import com.geovault.places.domain.PlacesRemoteDataSource
import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.model.PlaceKey

fun sampleContent(
    name: String = "Camp",
    description: String = "",
    longitude: Double = 20.0,
    latitude: Double = 10.0,
    address: String? = null,
): PlaceContent {
    return PlaceContent(
        name = name,
        description = description,
        location = LonLat(longitude = longitude, latitude = latitude),
        address = address,
    )
}

fun samplePlace(
    key: PlaceKey = PlaceKey.server(1),
    serverId: Int? = 1,
    name: String = "Camp",
    description: String = "",
    longitude: Double = 20.0,
    latitude: Double = 10.0,
    address: String? = null,
    createdAt: String? = "2026-01-01",
    pending: PendingChange? = null,
): Place {
    return Place(
        key = key,
        serverId = serverId,
        content = sampleContent(
            name = name,
            description = description,
            longitude = longitude,
            latitude = latitude,
            address = address,
        ),
        createdAt = createdAt,
        pending = pending,
    )
}

class InMemoryPlacesStore(
    initial: List<Place> = emptyList(),
) : PlacesMutableStore {
    private var items: List<Place> = initial

    override fun places(): List<Place> = items

    override suspend fun upsert(place: Place) {
        items = PlacesRecords.upsert(items, place)
    }

    override suspend fun remove(key: PlaceKey) {
        items = PlacesRecords.remove(items, key)
    }

    override suspend fun commitSynced(oldKey: PlaceKey, serverPlace: Place) {
        items = PlacesRecords.commitSynced(items, oldKey, serverPlace)
    }

    override suspend fun applyServerSnapshot(server: List<Place>, lastSyncMillis: Long) {
        items = PlacesRecords.applyServerSnapshot(items, server)
    }
}

class FakePlacesRemote(
    var onFetchPlaces: suspend () -> List<Place> = { emptyList() },
    var onFetchPlace: suspend (Int) -> Place = { error("fetchPlace unused") },
    var onCreatePlace: suspend (Place) -> Place = { error("createPlace unused") },
    var onUpdatePlace: suspend (Int, Place) -> Place = { _, _ -> error("updatePlace unused") },
    var onDeletePlace: suspend (Int) -> Unit = { error("deletePlace unused") },
) : PlacesRemoteDataSource {
    val created = mutableListOf<Place>()
    val updated = mutableListOf<Pair<Int, Place>>()
    val deleted = mutableListOf<Int>()

    override suspend fun fetchPlaces(): List<Place> = onFetchPlaces()

    override suspend fun fetchPlace(id: Int): Place = onFetchPlace(id)

    override suspend fun createPlace(place: Place): Place {
        created += place
        return onCreatePlace(place)
    }

    override suspend fun updatePlace(id: Int, place: Place): Place {
        updated += id to place
        return onUpdatePlace(id, place)
    }

    override suspend fun deletePlace(id: Int) {
        deleted += id
        onDeletePlace(id)
    }
}

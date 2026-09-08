package com.geovault.places.data

import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey

object PlacesRecords {
    fun upsert(places: List<Place>, place: Place): List<Place> {
        val without = places.filterNot { it.key == place.key }
        return listOf(place) + without
    }

    fun remove(places: List<Place>, key: PlaceKey): List<Place> {
        return places.filterNot { it.key == key }
    }

    fun commitSynced(places: List<Place>, oldKey: PlaceKey, serverPlace: Place): List<Place> {
        val serverId = serverPlace.serverId ?: return remove(places, oldKey)
        val synced = serverPlace.syncedFromServer(serverId, serverPlace.createdAt)
        return upsert(remove(places, oldKey), synced)
    }

    fun applyServerSnapshot(local: List<Place>, server: List<Place>): List<Place> {
        val pending = local.filter { it.pending != null }
        val pendingServerIds = pending.mapNotNull { it.serverId }.toSet()
        val incoming = server.filter { place ->
            val serverId = place.serverId
            serverId == null || serverId !in pendingServerIds
        }
        return pending + incoming
    }
}

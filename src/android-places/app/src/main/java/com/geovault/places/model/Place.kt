package com.geovault.places.model

import com.geovault.common.geo.LonLat

data class PlaceContent(
    val name: String,
    val description: String = "",
    val location: LonLat,
    val address: String? = null,
)

sealed class PendingChange {
    data object Create : PendingChange()
    data class Update(val baseline: PlaceContent) : PendingChange()
    data class Delete(val baseline: PlaceContent) : PendingChange()
}

data class Place(
    val key: PlaceKey,
    val serverId: Int?,
    val content: PlaceContent,
    val createdAt: String?,
    val pending: PendingChange?,
) {
    val isPending: Boolean
        get() = pending != null
    val isPendingDelete: Boolean
        get() = pending is PendingChange.Delete
    val isPendingCreate: Boolean
        get() = pending is PendingChange.Create
    val isPendingUpdate: Boolean
        get() = pending is PendingChange.Update

    fun syncedFromServer(serverId: Int, createdAt: String?): Place {
        return copy(
            key = PlaceKey.server(serverId),
            serverId = serverId,
            createdAt = createdAt ?: this.createdAt,
            pending = null,
        )
    }
}

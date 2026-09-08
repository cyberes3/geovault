package com.geovault.places.domain

import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.model.PlaceKey

class ConflictResolutionPolicy {
    fun hasServerChanged(original: PlaceContent, server: PlaceContent): Boolean {
        if (normalizeOptional(original.name) != normalizeOptional(server.name)) return true
        if (normalizeOptional(original.description) != normalizeOptional(server.description)) return true
        if (normalizeOptional(original.address) != normalizeOptional(server.address)) return true
        return original.location.longitude != server.location.longitude ||
            original.location.latitude != server.location.latitude
    }

    fun buildConflictedCopy(local: Place): Place {
        val shortKey = local.key.shortToken()
        return local.copy(
            key = PlaceKey.local(),
            serverId = null,
            content = local.content.copy(
                name = "${local.content.name.ifBlank { "Place" }} (conflict $shortKey)",
            ),
            pending = PendingChange.Create,
        )
    }

    private fun normalizeOptional(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}

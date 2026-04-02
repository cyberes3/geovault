package com.geovault.places.domain

import com.geovault.places.model.Feature

class ConflictResolutionPolicy {
    fun hasServerChanged(original: Feature, server: Feature): Boolean {
        if (original.properties.name != server.properties.name) return true
        if (original.properties.description != server.properties.description) return true
        if (original.properties.address != server.properties.address) return true
        return original.geometry.coordinates != server.geometry.coordinates
    }

    fun buildConflictedCopy(local: Feature): Feature {
        return local.copy(
            properties = local.properties.copy(
                database_id = null,
                name = (local.properties.name ?: "Place") + " - Conflicted",
            )
        )
    }
}

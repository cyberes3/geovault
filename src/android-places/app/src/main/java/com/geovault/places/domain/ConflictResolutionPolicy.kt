package com.geovault.places.domain

import com.geovault.places.model.Feature

class ConflictResolutionPolicy {
    fun hasServerChanged(original: Feature, server: Feature): Boolean {
        if (normalizeOptional(original.properties.name) != normalizeOptional(server.properties.name)) {
            return true
        }
        if (normalizeOptional(original.properties.description) !=
            normalizeOptional(server.properties.description)
        ) {
            return true
        }
        if (normalizeOptional(original.properties.address) !=
            normalizeOptional(server.properties.address)
        ) {
            return true
        }
        return point2d(original.geometry.coordinates) != point2d(server.geometry.coordinates)
    }

    fun buildConflictedCopy(local: Feature): Feature {
        return local.copy(
            properties = local.properties.copy(
                database_id = null,
                name = (local.properties.name ?: "Place") + " - Conflicted",
            ),
        )
    }

    private fun normalizeOptional(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    private fun point2d(coordinates: List<Double>): List<Double> =
        coordinates.take(2)
}

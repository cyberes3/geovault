package com.geovault.places.domain

import com.geovault.places.model.Place

data class PlacesListSections(
    val waitingToSync: List<Place>,
    val saved: List<Place>,
)

object PlacesListProjection {
    fun filter(places: List<Place>, query: String): PlacesListSections {
        val visible = places.filter { !it.isPendingDelete }
        val needle = query.trim()
        val filtered = if (needle.isEmpty()) {
            visible
        } else {
            visible.filter { matches(it, needle) }
        }
        return PlacesListSections(
            waitingToSync = filtered.filter { it.isPending },
            saved = filtered.filter { !it.isPending },
        )
    }

    fun exportable(places: List<Place>): List<Place> {
        return places.filter { !it.isPendingDelete }
    }

    private fun matches(place: Place, query: String): Boolean {
        return place.content.name.contains(query, ignoreCase = true) ||
            place.content.description.contains(query, ignoreCase = true)
    }
}

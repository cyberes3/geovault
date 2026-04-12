package com.geovault.common.maps.core

import com.geovault.common.maps.model.OPTION_SATELLITE
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.OPTION_STREET_DARK
import com.geovault.common.maps.model.OPTION_TOPO
import com.geovault.common.maps.model.SOURCE_GOOGLE_HYBRID_FALLBACK
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM

internal object MapSourcePolicy {
    fun normalizeSelection(raw: String): String {
        return when (raw) {
            SOURCE_OSM, SOURCE_MAPTILER_STREETS, OPTION_STREET -> OPTION_STREET
            SOURCE_MAPTILER_STREETS_DARK, OPTION_STREET_DARK -> OPTION_STREET_DARK
            SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE -> OPTION_SATELLITE
            SOURCE_MAPTILER_TOPO, OPTION_TOPO -> OPTION_TOPO
            else -> OPTION_STREET
        }
    }

    fun availableSelections(
        isAuthenticated: Boolean,
        hasMapTilerStreetDark: Boolean,
        hasMapTilerTopo: Boolean,
    ): List<String> {
        val options = mutableListOf(OPTION_STREET)
        if (isAuthenticated && hasMapTilerStreetDark) options.add(OPTION_STREET_DARK)
        if (isAuthenticated) options.add(OPTION_SATELLITE)
        if (isAuthenticated && hasMapTilerTopo) options.add(OPTION_TOPO)
        return options
    }

    fun nextSelection(current: String, availableSelections: List<String>): String {
        val available = availableSelections.ifEmpty { listOf(OPTION_STREET) }
        val normalized = normalizeSelection(current)
        val currentIndex = available.indexOf(normalized)
        val safeIndex = if (currentIndex >= 0) currentIndex else 0
        return available[(safeIndex + 1) % available.size]
    }

    fun sanitizeSelection(selection: String, availableSelections: List<String>): String {
        val normalized = normalizeSelection(selection)
        return if (normalized in availableSelections) normalized else OPTION_STREET
    }

    fun effectiveStreetSource(
        isAuthenticated: Boolean,
        hasMapTilerStreets: Boolean,
    ): String {
        return if (isAuthenticated && hasMapTilerStreets) SOURCE_MAPTILER_STREETS else SOURCE_OSM
    }

    fun effectiveSource(
        selectedOption: String,
        availableSelections: List<String>,
        streetSourceId: String,
        hasMapTilerStreetDark: Boolean,
        isAuthenticated: Boolean,
        hasMapTilerHybrid: Boolean,
        hasMapTilerTopo: Boolean,
    ): String {
        return when (sanitizeSelection(selectedOption, availableSelections)) {
            OPTION_STREET -> streetSourceId
            OPTION_STREET_DARK -> if (isAuthenticated && hasMapTilerStreetDark) SOURCE_MAPTILER_STREETS_DARK else streetSourceId
            OPTION_SATELLITE -> {
                if (!isAuthenticated) {
                    streetSourceId
                } else if (hasMapTilerHybrid) {
                    SOURCE_MAPTILER_HYBRID
                } else {
                    SOURCE_GOOGLE_HYBRID_FALLBACK
                }
            }
            OPTION_TOPO -> if (isAuthenticated && hasMapTilerTopo) SOURCE_MAPTILER_TOPO else streetSourceId
            else -> streetSourceId
        }
    }
}

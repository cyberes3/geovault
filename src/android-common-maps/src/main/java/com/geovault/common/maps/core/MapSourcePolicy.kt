package com.geovault.common.maps.core

import com.geovault.common.maps.model.OPTION_SATELLITE
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.OPTION_STREET_DARK
import com.geovault.common.maps.model.OPTION_TOPO
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM

internal object MapSourcePolicy {
    fun normalizeSelection(raw: String): String {
        return when (raw) {
            SOURCE_OSM, SOURCE_MAPTILER_STREETS, OPTION_STREET -> OPTION_STREET
            SOURCE_MAPTILER_STREETS_DARK, OPTION_STREET_DARK -> OPTION_STREET
            SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE -> OPTION_SATELLITE
            SOURCE_MAPTILER_TOPO, OPTION_TOPO -> OPTION_TOPO
            else -> OPTION_STREET
        }
    }

    /**
     * User-visible basemap cycle: Street / Satellite / Topo. MapTiler dark is applied at night
     * via [effectiveStreetSource], not as a separate picker option.
     */
    fun availableSelections(
        hasMapTilerTopo: Boolean,
        hasSatellite: Boolean,
    ): List<String> {
        val options = mutableListOf(OPTION_STREET)
        if (hasSatellite) options.add(OPTION_SATELLITE)
        if (hasMapTilerTopo) options.add(OPTION_TOPO)
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

    /**
     * Resolves the tile source id for the Street slot. At night, MapTiler OpenStreetMap Dark
     * replaces MapTiler Streets when the server provides it; otherwise MapTiler Streets stays active.
     */
    fun effectiveStreetSource(
        isNight: Boolean,
        hasMapTilerStreets: Boolean,
        hasMapTilerStreetDark: Boolean,
        hasOsm: Boolean,
    ): String {
        return when {
            isNight && hasMapTilerStreetDark -> SOURCE_MAPTILER_STREETS_DARK
            isNight && hasMapTilerStreets -> SOURCE_MAPTILER_STREETS
            !isNight && hasMapTilerStreets -> SOURCE_MAPTILER_STREETS
            hasOsm -> SOURCE_OSM
            else -> SOURCE_MAPTILER_STREETS
        }
    }

    fun effectiveSource(
        selectedOption: String,
        availableSelections: List<String>,
        streetSourceId: String,
        hasMapTilerHybrid: Boolean,
        hasMapTilerTopo: Boolean,
    ): String {
        return when (sanitizeSelection(selectedOption, availableSelections)) {
            OPTION_STREET -> streetSourceId
            OPTION_SATELLITE ->
                if (hasMapTilerHybrid) SOURCE_MAPTILER_HYBRID else streetSourceId
            OPTION_TOPO -> if (hasMapTilerTopo) SOURCE_MAPTILER_TOPO else streetSourceId
            else -> streetSourceId
        }
    }
}

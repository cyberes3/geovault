package com.geovault.common.maps.core

import com.geovault.common.maps.model.OPTION_SATELLITE
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.OPTION_TOPO
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM
import com.geovault.common.maps.model.SOURCE_OSM_DARK

internal object MapSourcePolicy {
    fun normalizeSelection(raw: String): String {
        return when (raw) {
            SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS, OPTION_STREET -> OPTION_STREET
            SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE -> OPTION_SATELLITE
            SOURCE_MAPTILER_TOPO, OPTION_TOPO -> OPTION_TOPO
            else -> OPTION_STREET
        }
    }

    fun nextSelection(current: String): String {
        return when (normalizeSelection(current)) {
            OPTION_STREET -> OPTION_SATELLITE
            OPTION_SATELLITE -> OPTION_TOPO
            else -> OPTION_STREET
        }
    }

    fun effectiveStreetSource(
        isDarkMode: Boolean,
        hasOsmDark: Boolean,
        hasMapTilerStreets: Boolean,
    ): String {
        if (isDarkMode) {
            return if (hasOsmDark) SOURCE_OSM_DARK else SOURCE_OSM
        }
        return if (hasMapTilerStreets) SOURCE_MAPTILER_STREETS else SOURCE_OSM
    }

    fun effectiveSource(
        selectedOption: String,
        effectiveStreetSourceId: String,
        hasMapTilerHybrid: Boolean,
        hasMapTilerTopo: Boolean,
    ): String {
        return when (normalizeSelection(selectedOption)) {
            OPTION_STREET -> effectiveStreetSourceId
            OPTION_SATELLITE -> if (hasMapTilerHybrid) SOURCE_MAPTILER_HYBRID else effectiveStreetSourceId
            OPTION_TOPO -> if (hasMapTilerTopo) SOURCE_MAPTILER_TOPO else effectiveStreetSourceId
            else -> effectiveStreetSourceId
        }
    }
}

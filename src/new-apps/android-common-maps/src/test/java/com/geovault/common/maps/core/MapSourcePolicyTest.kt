package com.geovault.common.maps.core

import com.geovault.common.maps.model.OPTION_SATELLITE
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.OPTION_TOPO
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM
import com.geovault.common.maps.model.SOURCE_OSM_DARK
import org.junit.Assert.assertEquals
import org.junit.Test

class MapSourcePolicyTest {
    @Test
    fun normalizeSelection_handlesLegacySourceIds() {
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_OSM))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_OSM_DARK))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_STREETS))
        assertEquals(OPTION_SATELLITE, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_HYBRID))
        assertEquals(OPTION_TOPO, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_TOPO))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection("unknown"))
    }

    @Test
    fun nextSelection_cyclesStreetSatelliteTopo() {
        assertEquals(OPTION_SATELLITE, MapSourcePolicy.nextSelection(OPTION_STREET))
        assertEquals(OPTION_TOPO, MapSourcePolicy.nextSelection(OPTION_SATELLITE))
        assertEquals(OPTION_STREET, MapSourcePolicy.nextSelection(OPTION_TOPO))
    }

    @Test
    fun effectiveStreetSource_prefersDarkInNightMode() {
        assertEquals(
            SOURCE_OSM_DARK,
            MapSourcePolicy.effectiveStreetSource(
                isDarkMode = true,
                hasOsmDark = true,
                hasMapTilerStreets = true,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveStreetSource(
                isDarkMode = true,
                hasOsmDark = false,
                hasMapTilerStreets = true,
            ),
        )
    }

    @Test
    fun effectiveStreetSource_prefersMaptilerStreetsInLightMode() {
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveStreetSource(
                isDarkMode = false,
                hasOsmDark = true,
                hasMapTilerStreets = true,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveStreetSource(
                isDarkMode = false,
                hasOsmDark = true,
                hasMapTilerStreets = false,
            ),
        )
    }

    @Test
    fun effectiveSource_fallsBackToEffectiveStreet() {
        assertEquals(
            SOURCE_MAPTILER_HYBRID,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_SATELLITE,
                effectiveStreetSourceId = SOURCE_OSM,
                hasMapTilerHybrid = true,
                hasMapTilerTopo = false,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_SATELLITE,
                effectiveStreetSourceId = SOURCE_OSM,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = false,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_TOPO,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_TOPO,
                effectiveStreetSourceId = SOURCE_OSM,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = true,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_TOPO,
                effectiveStreetSourceId = SOURCE_OSM,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = false,
            ),
        )
    }
}

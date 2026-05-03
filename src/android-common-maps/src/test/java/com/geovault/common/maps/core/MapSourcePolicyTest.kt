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
import org.junit.Assert.assertEquals
import org.junit.Test

class MapSourcePolicyTest {
    @Test
    fun normalizeSelection_handlesMappedSourceIds() {
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_OSM))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_STREETS))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_STREETS_DARK))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection(OPTION_STREET_DARK))
        assertEquals(OPTION_SATELLITE, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_HYBRID))
        assertEquals(OPTION_TOPO, MapSourcePolicy.normalizeSelection(SOURCE_MAPTILER_TOPO))
        assertEquals(OPTION_STREET, MapSourcePolicy.normalizeSelection("unknown"))
    }

    @Test
    fun availableSelections_threeStyleCycleNoSeparateDarkStreet() {
        assertEquals(
            listOf(OPTION_STREET, OPTION_SATELLITE, OPTION_TOPO),
            MapSourcePolicy.availableSelections(
                hasMapTilerTopo = true,
                hasSatellite = true,
            ),
        )
        assertEquals(
            listOf(OPTION_STREET, OPTION_SATELLITE),
            MapSourcePolicy.availableSelections(
                hasMapTilerTopo = false,
                hasSatellite = true,
            ),
        )
        assertEquals(
            listOf(OPTION_STREET),
            MapSourcePolicy.availableSelections(
                hasMapTilerTopo = false,
                hasSatellite = false,
            ),
        )
    }

    @Test
    fun nextSelection_cyclesStreetSatelliteTopo() {
        val available = listOf(OPTION_STREET, OPTION_SATELLITE, OPTION_TOPO)
        assertEquals(OPTION_SATELLITE, MapSourcePolicy.nextSelection(OPTION_STREET, available))
        assertEquals(OPTION_TOPO, MapSourcePolicy.nextSelection(OPTION_SATELLITE, available))
        assertEquals(OPTION_STREET, MapSourcePolicy.nextSelection(OPTION_TOPO, available))
        assertEquals(OPTION_SATELLITE, MapSourcePolicy.nextSelection(OPTION_STREET_DARK, available))
    }

    @Test
    fun effectiveStreetSource_nightPrefersMapTilerDarkThenLightNeverOsmForDark() {
        assertEquals(
            SOURCE_MAPTILER_STREETS_DARK,
            MapSourcePolicy.effectiveStreetSource(
                isNight = true,
                hasMapTilerStreets = true,
                hasMapTilerStreetDark = true,
                hasOsm = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveStreetSource(
                isNight = true,
                hasMapTilerStreets = true,
                hasMapTilerStreetDark = false,
                hasOsm = true,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveStreetSource(
                isNight = true,
                hasMapTilerStreets = false,
                hasMapTilerStreetDark = false,
                hasOsm = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveStreetSource(
                isNight = false,
                hasMapTilerStreets = true,
                hasMapTilerStreetDark = true,
                hasOsm = true,
            ),
        )
        assertEquals(
            SOURCE_OSM,
            MapSourcePolicy.effectiveStreetSource(
                isNight = false,
                hasMapTilerStreets = false,
                hasMapTilerStreetDark = false,
                hasOsm = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveStreetSource(
                isNight = false,
                hasMapTilerStreets = false,
                hasMapTilerStreetDark = false,
                hasOsm = false,
            ),
        )
    }

    @Test
    fun effectiveSource_streetUsesStreetSlotSatelliteTopoUnchanged() {
        val available = listOf(OPTION_STREET, OPTION_SATELLITE, OPTION_TOPO)
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_STREET,
                availableSelections = available,
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = true,
                hasMapTilerTopo = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_STREET_DARK,
                availableSelections = available,
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = true,
                hasMapTilerTopo = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_HYBRID,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_SATELLITE,
                availableSelections = available,
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = true,
                hasMapTilerTopo = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_SATELLITE,
                availableSelections = listOf(OPTION_STREET, OPTION_SATELLITE),
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = false,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_TOPO,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_TOPO,
                availableSelections = available,
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = true,
            ),
        )
        assertEquals(
            SOURCE_MAPTILER_STREETS,
            MapSourcePolicy.effectiveSource(
                selectedOption = OPTION_TOPO,
                availableSelections = listOf(OPTION_STREET, OPTION_SATELLITE),
                streetSourceId = SOURCE_MAPTILER_STREETS,
                hasMapTilerHybrid = false,
                hasMapTilerTopo = false,
            ),
        )
    }
}

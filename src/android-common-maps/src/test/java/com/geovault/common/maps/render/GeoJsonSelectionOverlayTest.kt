package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.style.layers.Property

class GeoJsonSelectionOverlayTest {
    private val config = GeoJsonSelectionOverlayConfig()
    private val alpha = MapRenderPoint(
        id = "a",
        latitude = 10.0,
        longitude = 20.0,
        title = "Alpha",
        iconImageId = CommonMapIconIds.MARKER_DEFAULT,
        iconSize = 1f,
    )
    private val beta = MapRenderPoint(
        id = "b",
        latitude = 11.0,
        longitude = 21.0,
        title = "Beta",
        iconImageId = CommonMapIconIds.MARKER_NAV_TARGET,
        iconSize = 1f,
    )

    @Test
    fun styledPoint_nullId_returnsNull() {
        assertNull(
            GeoJsonSelectionOverlay.styledPoint(
                selectedId = null,
                sourcePoints = listOf(alpha),
                config = config,
                style = null,
            ),
        )
    }

    @Test
    fun styledPoint_missingId_returnsNull() {
        assertNull(
            GeoJsonSelectionOverlay.styledPoint(
                selectedId = "missing",
                sourcePoints = listOf(alpha),
                config = config,
                style = null,
            ),
        )
    }

    @Test
    fun styledPoint_defaultStyle_usesSelectedIconAndClearsTitle() {
        val styled = GeoJsonSelectionOverlay.styledPoint(
            selectedId = "a",
            sourcePoints = listOf(alpha, beta),
            config = config,
            style = null,
        )

        assertEquals("a", styled?.id)
        assertEquals(10.0, styled?.latitude)
        assertEquals(20.0, styled?.longitude)
        assertEquals(CommonMapIconIds.MARKER_SELECTED, styled?.iconImageId)
        assertEquals(1.08f, styled?.iconSize)
        assertNull(styled?.title)
    }

    @Test
    fun styledPoint_keepLabels_preservesTitle() {
        val styled = GeoJsonSelectionOverlay.styledPoint(
            selectedId = "a",
            sourcePoints = listOf(alpha),
            config = GeoJsonSelectionOverlayConfig(showPointTextLabels = true),
            style = null,
        )

        assertEquals("Alpha", styled?.title)
    }

    @Test
    fun styledPoint_customStyler_wins() {
        val styled = GeoJsonSelectionOverlay.styledPoint(
            selectedId = "b",
            sourcePoints = listOf(alpha, beta),
            config = config,
            style = { point ->
                point.copy(
                    iconImageId = CommonMapIconIds.MARKER_NAV_TARGET,
                    iconSize = 1.2f,
                    title = null,
                )
            },
        )

        assertEquals(CommonMapIconIds.MARKER_NAV_TARGET, styled?.iconImageId)
        assertEquals(1.2f, styled?.iconSize)
        assertNull(styled?.title)
    }

    @Test
    fun styledPoint_deleteReconcile_missingAfterSourceChange() {
        assertNull(
            GeoJsonSelectionOverlay.styledPoint(
                selectedId = "a",
                sourcePoints = listOf(beta),
                config = config,
                style = null,
            ),
        )
    }

    @Test
    fun layout_centerAnchor_putsPivotOnScreenPoint() {
        val placement = GeoJsonSelectionOverlayLayout.placement(
            screenX = 100f,
            screenY = 200f,
            width = 20f,
            height = 20f,
            iconAnchor = Property.ICON_ANCHOR_CENTER,
        )
        assertEquals(10f, placement.pivotX)
        assertEquals(10f, placement.pivotY)
        assertEquals(90f, placement.translationX)
        assertEquals(190f, placement.translationY)
    }

    @Test
    fun layout_bottomAnchor_putsPivotOnScreenPoint() {
        val placement = GeoJsonSelectionOverlayLayout.placement(
            screenX = 100f,
            screenY = 200f,
            width = 20f,
            height = 40f,
            iconAnchor = Property.ICON_ANCHOR_BOTTOM,
        )
        assertEquals(10f, placement.pivotX)
        assertEquals(40f, placement.pivotY)
        assertEquals(90f, placement.translationX)
        assertEquals(160f, placement.translationY)
    }
}

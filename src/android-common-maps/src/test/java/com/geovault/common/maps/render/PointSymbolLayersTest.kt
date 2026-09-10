package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PointSymbolLayersTest {

    @Test
    fun inPaintOrder_isIconThenLabel() {
        val layers = PointSymbolLayers(icon = "icon-layer", label = "label-layer")
        assertEquals(listOf("icon-layer", "label-layer"), layers.inPaintOrder())
    }

    @Test
    fun inPaintOrder_nullLabel_isIconOnly() {
        val layers = PointSymbolLayers(icon = "icon-layer", label = null)
        assertEquals(listOf("icon-layer"), layers.inPaintOrder())
    }
}

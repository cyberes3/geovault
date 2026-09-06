package com.geovault.common.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultListCardHighlightColorsTest {
    private val surface = Color(0xFFFFFFFF)

    @Test
    fun `light default uses surface fill and main blue border`() {
        assertEquals(surface, GeoVaultListCardHighlightColors.fillColor(false, isLight = true, surfaceColor = surface))
        assertEquals(GeoVaultColorTokens.MainBlue, GeoVaultListCardHighlightColors.borderColor(false, isLight = true))
    }

    @Test
    fun `light highlighted uses purple fill and border`() {
        assertEquals(GeoVaultColorTokens.Purple200, GeoVaultListCardHighlightColors.fillColor(true, isLight = true, surfaceColor = surface))
        assertEquals(GeoVaultColorTokens.Purple500, GeoVaultListCardHighlightColors.borderColor(true, isLight = true))
    }

    @Test
    fun `dark highlighted uses translucent purple fill and main purple border`() {
        assertEquals(
            GeoVaultColorTokens.MainPurple.copy(alpha = 0.26f),
            GeoVaultListCardHighlightColors.fillColor(true, isLight = false, surfaceColor = surface),
        )
        assertEquals(GeoVaultColorTokens.MainPurple, GeoVaultListCardHighlightColors.borderColor(true, isLight = false))
    }

    @Test
    fun `dark default uses surface fill and main blue border`() {
        assertEquals(surface, GeoVaultListCardHighlightColors.fillColor(false, isLight = false, surfaceColor = surface))
        assertEquals(GeoVaultColorTokens.MainBlue, GeoVaultListCardHighlightColors.borderColor(false, isLight = false))
    }

    @Test
    fun `highlighted icon tile uses purple disk and tint in light mode`() {
        assertEquals(GeoVaultColorTokens.Purple100, GeoVaultListCardHighlightColors.iconTileDiskColor(true, isLight = true))
        assertEquals(GeoVaultColorTokens.MainPurple, GeoVaultListCardHighlightColors.iconTint(true))
    }

    @Test
    fun `default icon tile uses blue disk and tint in light mode`() {
        assertEquals(GeoVaultColorTokens.Blue100, GeoVaultListCardHighlightColors.iconTileDiskColor(false, isLight = true))
        assertEquals(GeoVaultColorTokens.MainBlue, GeoVaultListCardHighlightColors.iconTint(false))
    }

    @Test
    fun `light selected uses purple100 fill and emphasis blue border`() {
        assertEquals(
            GeoVaultColorTokens.Purple100,
            GeoVaultListCardHighlightColors.selectedFillColor(true, isLight = true, surfaceColor = surface),
        )
        assertEquals(GeoVaultColorTokens.MainBlue, GeoVaultListCardHighlightColors.emphasisBorderColor(false))
        assertEquals(GeoVaultColorTokens.MainYellow, GeoVaultListCardHighlightColors.emphasisBorderColor(true))
    }

    @Test
    fun `dark selected uses translucent blue fill`() {
        assertEquals(
            GeoVaultColorTokens.MainBlue.copy(alpha = 0.14f),
            GeoVaultListCardHighlightColors.selectedFillColor(true, isLight = false, surfaceColor = surface),
        )
    }

    @Test
    fun `trailingActionTint uses purple when highlighted`() {
        val default = GeoVaultColorTokens.Gray500
        assertEquals(GeoVaultColorTokens.MainPurple, GeoVaultListCardHighlightColors.trailingActionTint(true, default))
        assertEquals(default, GeoVaultListCardHighlightColors.trailingActionTint(false, default))
    }
}

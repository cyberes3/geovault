package com.geovault.tracker.ui

import android.content.Context
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.parseHexToColorInt

object TrackerChevronStylePolicy {
    val TrackerRowChevronSize: Dp = 18.dp
    val DefaultAddRowTint: Color = GeoVaultColorTokens.PrimaryBlue

    fun tintForTrackerColorHex(colorHex: String?, context: Context): Color {
        val normalized = colorHex?.trim()?.let { if (it.startsWith("#")) it else "#$it" }?.takeIf { it.isNotEmpty() }
        if (normalized == null) return GeoVaultColorTokens.PrimaryBlue
        return try {
            Color(parseHexToColorInt(colorHex, context))
        } catch (_: Exception) {
            GeoVaultColorTokens.PrimaryBlue
        }
    }
}

@Composable
fun TrackerChevronIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_track),
        contentDescription = null,
        tint = tint,
        modifier = modifier,
    )
}

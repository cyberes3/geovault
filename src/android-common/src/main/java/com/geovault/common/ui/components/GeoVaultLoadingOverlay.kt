package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultLoadingOverlay(
    isVisible: Boolean,
    title: String = "Loading...",
    subtext: String? = null,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    blockInput: Boolean = true,
    showSubtext: Boolean = true,
) {
    if (!isVisible) return

    val interactionSource = remember { MutableInteractionSource() }
    val scrimModifier = if (onTap != null) {
        modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.ScrimMedium)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onTap() }
    } else {
        modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.ScrimMedium)
            .then(
                if (blockInput) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { }
                } else {
                    Modifier
                }
            )
    }

    Box(
        modifier = scrimModifier,
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(2.dp, GeoVaultColorTokens.MainBlue),
            backgroundColor = GeoVaultColorTokens.Surface
        ) {
            Column(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GeoVaultLoadingSpinner(spinnerSize = 22.dp)
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(top = 10.dp),
                        color = GeoVaultColorTokens.TextSecondary,
                        fontSize = 16.sp
                    )
                }
                if (showSubtext && !subtext.isNullOrBlank()) {
                    Text(
                        text = subtext,
                        modifier = Modifier.padding(top = 4.dp),
                        color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}


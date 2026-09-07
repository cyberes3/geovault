package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultServerConnectionFailureOverlay(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .background(GeoVaultColorTokens.ScrimStrong)
            .clickable(
                enabled = true,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .padding(horizontal = 24.dp),
            shape = MaterialTheme.shapes.medium,
            color = GeoVaultColorTokens.ErrorSurfaceLight,
            border = BorderStroke(3.dp, GeoVaultColorTokens.Error),
            elevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = GeoVaultColorTokens.Error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

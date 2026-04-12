package com.geovault.common.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import kotlinx.coroutines.delay

@Composable
fun GeoVaultLoadingSpinner(
    modifier: Modifier = Modifier,
    spinnerSize: Dp = 28.dp,
    strokeWidth: Dp = 2.5.dp,
    color: Color = GeoVaultColorTokens.PrimaryBlue,
    bottomText: String? = null,
) {
    if (bottomText != null) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeoVaultLoadingSpinnerArc(
                spinnerSize = spinnerSize,
                strokeWidth = strokeWidth,
                color = color,
            )
            Text(
                text = bottomText,
                style = MaterialTheme.typography.body2,
            )
        }
    } else {
        GeoVaultLoadingSpinnerArc(
            modifier = modifier,
            spinnerSize = spinnerSize,
            strokeWidth = strokeWidth,
            color = color,
        )
    }
}

@Composable
private fun GeoVaultLoadingSpinnerArc(
    modifier: Modifier = Modifier,
    spinnerSize: Dp = 28.dp,
    strokeWidth: Dp = 2.5.dp,
    color: Color = GeoVaultColorTokens.PrimaryBlue,
) {
    var rotationDegrees by androidx.compose.runtime.remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            rotationDegrees = (rotationDegrees + 10f) % 360f
            delay(30L)
        }
    }

    Canvas(
        modifier = modifier
            .size(spinnerSize)
            .rotate(rotationDegrees)
    )
    {
        val strokePx = strokeWidth.toPx()
        val spinnerArcDiameter = size.minDimension * (24f / 28f)
        val radiusInset = strokePx / 2f
        val arcSize = Size(
            width = spinnerArcDiameter - strokePx,
            height = spinnerArcDiameter - strokePx
        )
        val arcTopLeft = Offset(
            x = (size.width - spinnerArcDiameter) / 2f + radiusInset,
            y = (size.height - spinnerArcDiameter) / 2f + radiusInset
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Butt)
        )
    }
}

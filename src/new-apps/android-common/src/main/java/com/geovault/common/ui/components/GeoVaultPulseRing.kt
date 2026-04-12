package com.geovault.common.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

data class GeoVaultPulseRingConfig(
    val cycleDurationMs: Long = 1800L,
    val minRadiusFraction: Float = 0.5f,
    val maxRadiusFraction: Float = 0.85f,
    val strokeWidthDp: Float = 2f,
    val maxAlpha: Float = 0.5f,
)

/**
 * Wraps [content] in a container with an expanding, fading ring pulse behind it.
 *
 * The ring is drawn as a stroked circle on a Canvas layer beneath the content,
 * driven by a frame-based timer so it animates regardless of system animator
 * scale settings.
 *
 * The max ring radius is automatically clamped so it never extends beyond the
 * window edges, based on the component's measured position.
 */
@Composable
fun GeoVaultPulseRing(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    config: GeoVaultPulseRingConfig = GeoVaultPulseRingConfig(),
    content: @Composable BoxScope.() -> Unit,
) {
    val rawProgress = rememberPulseProgress(cycleDurationMs = config.cycleDurationMs)
    val view = LocalView.current
    var maxSafeRadiusPx by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val centerX = (bounds.left + bounds.right) / 2f
                val centerY = (bounds.top + bounds.bottom) / 2f
                val windowWidth = view.width.toFloat()
                val windowHeight = view.height.toFloat()
                maxSafeRadiusPx = min(
                    min(centerX, windowWidth - centerX),
                    min(centerY, windowHeight - centerY),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val minRadius = this.size.minDimension * config.minRadiusFraction
            var maxRadius = this.size.minDimension * config.maxRadiusFraction
            val strokeWidthPx = config.strokeWidthDp * density

            val safeLimit = maxSafeRadiusPx
            if (safeLimit != null) {
                maxRadius = min(maxRadius, safeLimit - (strokeWidthPx / 2f))
            }
            maxRadius = maxRadius.coerceAtLeast(minRadius)

            val radius = minRadius + (rawProgress * (maxRadius - minRadius))
            val alpha = config.maxAlpha * (1f - rawProgress)

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx),
            )
        }

        content()
    }
}

@Composable
private fun rememberPulseProgress(cycleDurationMs: Long): Float {
    var progress by remember(cycleDurationMs) { mutableFloatStateOf(0f) }
    LaunchedEffect(cycleDurationMs) {
        val cycleNanos = (cycleDurationMs * 1_000_000L).coerceAtLeast(1L)
        while (true) {
            withFrameNanos { frameTimeNanos ->
                progress = (frameTimeNanos % cycleNanos).toFloat() / cycleNanos.toFloat()
            }
        }
    }
    return progress
}

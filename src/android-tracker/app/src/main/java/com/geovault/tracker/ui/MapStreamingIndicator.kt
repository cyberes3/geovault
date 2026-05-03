package com.geovault.tracker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.presentation.TrackerMapStreamingStatus
import com.geovault.tracker.presentation.TrackerMapStreamingStatusUiModel

@Composable
fun MapStreamingIndicator(
    model: TrackerMapStreamingStatusUiModel,
    modifier: Modifier = Modifier,
) {
    if (model.status == TrackerMapStreamingStatus.INACTIVE) return

    val dotColor = when (model.status) {
        TrackerMapStreamingStatus.LIVE -> GeoVaultColorTokens.Error
        TrackerMapStreamingStatus.CONNECTING,
        TrackerMapStreamingStatus.RECONNECTING -> GeoVaultColorTokens.MainYellow
        TrackerMapStreamingStatus.FAILED -> GeoVaultColorTokens.Gray400
        TrackerMapStreamingStatus.INACTIVE -> return
    }

    val label = when (model.status) {
        TrackerMapStreamingStatus.CONNECTING -> stringResource(R.string.map_streaming_connecting)
        TrackerMapStreamingStatus.RECONNECTING -> stringResource(R.string.map_streaming_reconnecting)
        TrackerMapStreamingStatus.LIVE -> {
            if (model.activeCount > 1) {
                stringResource(R.string.map_streaming_live_count, model.activeCount)
            } else {
                stringResource(R.string.map_streaming_live)
            }
        }
        TrackerMapStreamingStatus.FAILED -> stringResource(R.string.map_streaming_failed)
        TrackerMapStreamingStatus.INACTIVE -> return
    }

    val shouldPulse = model.status == TrackerMapStreamingStatus.CONNECTING ||
        model.status == TrackerMapStreamingStatus.RECONNECTING

    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colors.background.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamingDot(
            color = dotColor,
            pulse = shouldPulse,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun StreamingDot(
    color: Color,
    pulse: Boolean,
    modifier: Modifier = Modifier,
) {
    val dotAlpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "streaming_pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "streaming_dot_alpha",
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .alpha(dotAlpha)
            .background(
                color = color,
                shape = CircleShape,
            ),
    )
}

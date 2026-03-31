package com.geovault.common.ui.snackbar

import android.view.ViewConfiguration
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DISMISS_DELAY_MS = 15_000L
private const val DISMISS_DISTANCE_FRACTION = 0.35f
private const val DISMISS_SWIPE_ANIM_MS = 180
private const val SWIPE_RESET_ANIM_MS = 160
private const val SWIPE_ALPHA_FRACTION = 0.35f

@Composable
fun GeoVaultSnackbarHost(
    model: GeoVaultSnackbarModel?,
    onDismiss: () -> Unit,
    onAction: (actionId: String) -> Unit,
    modifier: Modifier = Modifier,
    style: GeoVaultSnackbarStyle = GeoVaultSnackbarDefaults.style(),
    stackBottomInset: Dp = 0.dp
) {
    if (model == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val minFlingVelocity = remember(context) { ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }

    val onDismissUpdated by rememberUpdatedState(onDismiss)
    val onActionUpdated by rememberUpdatedState(onAction)

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var translationX by remember(model.id, model.message) { mutableFloatStateOf(0f) }
    var alphaBar by remember(model.id, model.message) { mutableFloatStateOf(1f) }

    LaunchedEffect(model.id, model.message) {
        translationX = 0f
        alphaBar = 1f
    }

    LaunchedEffect(model.id, model.message) {
        delay(DISMISS_DELAY_MS)
        onDismissUpdated()
    }

    val barShape = RoundedCornerShape(style.cornerRadius)

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = stackBottomInset)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { barWidthPx = it.width.toFloat() }
                .graphicsLayer {
                    this.translationX = translationX
                    alpha = alphaBar
                }
                .clip(barShape)
                .background(style.background, barShape)
                .border(style.borderWidth, style.borderColor, barShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(model.id, barWidthPx, minFlingVelocity) {
                            var accumulatedDrag = 0f
                            var prevUptimeMillis = 0L
                            var lastVelocityX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    accumulatedDrag = 0f
                                    prevUptimeMillis = 0L
                                    lastVelocityX = 0f
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val now = change.uptimeMillis
                                    if (prevUptimeMillis > 0L) {
                                        val dt = (now - prevUptimeMillis).coerceAtLeast(1L)
                                        lastVelocityX = (dragAmount / dt) * 1000f
                                    }
                                    prevUptimeMillis = now
                                    accumulatedDrag += dragAmount
                                    translationX = accumulatedDrag
                                    val w = barWidthPx.coerceAtLeast(1f)
                                    val fraction = (abs(accumulatedDrag) / w).coerceIn(0f, 1f)
                                    alphaBar = 1f - fraction * SWIPE_ALPHA_FRACTION
                                },
                                onDragCancel = {
                                    val startT = translationX
                                    val startA = alphaBar
                                    scope.launch {
                                        coroutineScope {
                                            launch {
                                                animate(
                                                    initialValue = startT,
                                                    targetValue = 0f,
                                                    animationSpec = tween(SWIPE_RESET_ANIM_MS)
                                                ) { value, _ ->
                                                    translationX = value
                                                }
                                            }
                                            launch {
                                                animate(
                                                    initialValue = startA,
                                                    targetValue = 1f,
                                                    animationSpec = tween(SWIPE_RESET_ANIM_MS)
                                                ) { value, _ ->
                                                    alphaBar = value
                                                }
                                            }
                                        }
                                    }
                                    accumulatedDrag = 0f
                                },
                                onDragEnd = {
                                    val w = barWidthPx
                                    if (w <= 0f) {
                                        accumulatedDrag = 0f
                                        return@detectHorizontalDragGestures
                                    }
                                    val dismissByDistance = abs(accumulatedDrag) > w * DISMISS_DISTANCE_FRACTION
                                    val dismissByVelocity = abs(lastVelocityX) > minFlingVelocity
                                    val startT = translationX
                                    val startA = alphaBar
                                    val target =
                                        if (accumulatedDrag < 0f) -w else w
                                    if (dismissByDistance || dismissByVelocity) {
                                        scope.launch {
                                            coroutineScope {
                                                launch {
                                                    animate(
                                                        initialValue = startT,
                                                        targetValue = target,
                                                        animationSpec = tween(DISMISS_SWIPE_ANIM_MS)
                                                    ) { value, _ ->
                                                        translationX = value
                                                    }
                                                }
                                                launch {
                                                    animate(
                                                        initialValue = startA,
                                                        targetValue = 0f,
                                                        animationSpec = tween(DISMISS_SWIPE_ANIM_MS)
                                                    ) { value, _ ->
                                                        alphaBar = value
                                                    }
                                                }
                                            }
                                            onDismissUpdated()
                                        }
                                    } else {
                                        scope.launch {
                                            coroutineScope {
                                                launch {
                                                    animate(
                                                        initialValue = startT,
                                                        targetValue = 0f,
                                                        animationSpec = tween(SWIPE_RESET_ANIM_MS)
                                                    ) { value, _ ->
                                                        translationX = value
                                                    }
                                                }
                                                launch {
                                                    animate(
                                                        initialValue = startA,
                                                        targetValue = 1f,
                                                        animationSpec = tween(SWIPE_RESET_ANIM_MS)
                                                    ) { value, _ ->
                                                        alphaBar = value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    accumulatedDrag = 0f
                                }
                            )
                        }
                ) {
                    Text(
                        text = model.message,
                        color = style.messageColor,
                        style = MaterialTheme.typography.body2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val action = model.action
                if (action != null) {
                    Text(
                        text = action.label,
                        color = style.actionColor,
                        style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable {
                                onActionUpdated(action.actionId)
                                onDismissUpdated()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

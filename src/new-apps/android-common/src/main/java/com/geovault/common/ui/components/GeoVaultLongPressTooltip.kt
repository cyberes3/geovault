package com.geovault.common.ui.components

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.FloatingActionButtonDefaults
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

fun Modifier.trackGeoVaultTooltipBounds(onBounds: (Rect?) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        onBounds(
            Rect(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt(),
            ),
        )
    }

/**
 * Long-press tooltip matching legacy [android.view.View.setTooltipText]: uses a proxy [android.view.View]
 * and [ViewCompat.setTooltipText] so the platform shows the standard tooltip bubble.
 *
 * Call from the same composable that owns [interactionSource] and apply [Modifier.trackGeoVaultTooltipBounds]
 * on the interactive control so bounds stay in sync.
 */
@Composable
fun GeoVaultInstallLongPressTooltip(
    tooltipText: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    anchorBounds: Rect?,
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val rootView = LocalView.current
    val anchorProxyView = remember(rootView) {
        android.view.View(rootView.context).apply {
            isLongClickable = true
            isClickable = false
            alpha = 0f
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    fun updateAnchorProxyLayout() {
        val parent = anchorProxyView.parent as? android.view.ViewGroup ?: return
        val bounds = anchorBounds ?: return
        val rootLocation = IntArray(2)
        parent.getLocationInWindow(rootLocation)
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val layoutParams = anchorProxyView.layoutParams ?: android.view.ViewGroup.LayoutParams(width, height)
        layoutParams.width = width
        layoutParams.height = height
        anchorProxyView.layoutParams = layoutParams
        anchorProxyView.x = (bounds.left - rootLocation[0]).toFloat()
        anchorProxyView.y = (bounds.top - rootLocation[1]).toFloat()
    }

    LaunchedEffect(anchorBounds, anchorProxyView) {
        updateAnchorProxyLayout()
    }

    LaunchedEffect(rootView, anchorProxyView) {
        val parent = rootView as? android.view.ViewGroup ?: return@LaunchedEffect
        if (anchorProxyView.parent == null) {
            parent.addView(anchorProxyView, android.view.ViewGroup.LayoutParams(1, 1))
        }
    }
    DisposableEffect(rootView, anchorProxyView) {
        onDispose {
            ViewCompat.setTooltipText(anchorProxyView, null)
            (anchorProxyView.parent as? android.view.ViewGroup)?.removeView(anchorProxyView)
        }
    }

    LaunchedEffect(isPressed, tooltipText, enabled, anchorBounds, anchorProxyView) {
        if (!enabled || !isPressed) return@LaunchedEffect
        delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
        if (isPressed) {
            updateAnchorProxyLayout()
            ViewCompat.setTooltipText(anchorProxyView, tooltipText)
            val touchX = anchorProxyView.width * 0.5f
            val touchY = anchorProxyView.height * 0.5f
            anchorProxyView.performLongClick(touchX, touchY)
        }
    }

    LaunchedEffect(tooltipText, anchorProxyView) {
        ViewCompat.setTooltipText(anchorProxyView, tooltipText)
    }
}

@Composable
fun GeoVaultIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val tooltipText = tooltip?.takeIf { it.isNotBlank() }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = enabled,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
        )
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.trackGeoVaultTooltipBounds { anchorBounds = it },
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Icon-sized hit target using [Modifier.clickable] (for surfaces that are not [IconButton]).
 */
@Composable
fun GeoVaultClickableWithTooltip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val tooltipText = tooltip?.takeIf { it.isNotBlank() }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = enabled,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
        )
    }
    Box(
        modifier = modifier
            .trackGeoVaultTooltipBounds { anchorBounds = it }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

@Composable
fun GeoVaultFloatingActionButtonWithTooltip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    backgroundColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val tooltipText = tooltip?.takeIf { it.isNotBlank() }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = true,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
        )
    }
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.trackGeoVaultTooltipBounds { anchorBounds = it },
        interactionSource = interactionSource,
        shape = CircleShape,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
        content = content,
    )
}

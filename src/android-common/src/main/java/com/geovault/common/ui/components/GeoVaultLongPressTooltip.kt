package com.geovault.common.ui.components

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Lazy bounds tracking for tooltip anchoring. The expensive [onGloballyPositioned] callback is
 * not registered until the user actually starts pressing the control (observed via
 * [interactionSource]), at which point we begin tracking layout bounds for the tooltip proxy view.
 *
 * Once "armed" we never disarm — subsequent recompositions keep tracking bounds normally.
 *
 * This avoids paying the cost of layout callbacks for every tooltip-bearing control on screen
 * (e.g. every row in a long list) until the user actually long-presses one.
 */
fun Modifier.trackGeoVaultTooltipBounds(
    interactionSource: InteractionSource,
    onBounds: (Rect?) -> Unit,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    var armed by remember { mutableStateOf(false) }
    if (isPressed && !armed) armed = true
    if (!armed) {
        this
    } else {
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
    }
}

/**
 * Long-press tooltip backed by [android.view.View.setTooltipText]: uses a proxy [android.view.View]
 * and [ViewCompat.setTooltipText] so the platform shows the standard tooltip bubble.
 *
 * Call from the same composable that owns [interactionSource] and apply [Modifier.trackGeoVaultTooltipBounds]
 * on the interactive control so bounds stay in sync.
 *
 * When [suppressNextClickAfterTooltip] is non-null, a successful tooltip long-press sets it to `true` so the
 * caller can ignore the following [androidx.compose.foundation.clickable] / button `onClick` from the same
 * gesture (otherwise release would act like a tap).
 */
@Composable
fun GeoVaultInstallLongPressTooltip(
    tooltipText: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    anchorBounds: Rect?,
    suppressNextClickAfterTooltip: MutableState<Boolean>? = null,
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    // Defer all view allocation, root-view attachment and tooltip wiring until the user actually
    // starts pressing this control. This makes idle cost ~0 per tooltip call site, which matters
    // when many tooltip-bearing controls (e.g. tracker list rows) are composed at once.
    var armed by remember { mutableStateOf(false) }
    if (enabled && isPressed && !armed) armed = true
    if (!armed) return

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
        suppressNextClickAfterTooltip?.value = false
        delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
        if (isPressed) {
            updateAnchorProxyLayout()
            ViewCompat.setTooltipText(anchorProxyView, tooltipText)
            val touchX = anchorProxyView.width * 0.5f
            val touchY = anchorProxyView.height * 0.5f
            anchorProxyView.performLongClick(touchX, touchY)
            suppressNextClickAfterTooltip?.value = true
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
    val suppressNextClickAfterTooltip = if (tooltipText != null) {
        remember { mutableStateOf(false) }
    } else {
        null
    }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = enabled,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
            suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
        )
    }
    IconButton(
        onClick = {
            if (suppressNextClickAfterTooltip?.value == true) {
                suppressNextClickAfterTooltip.value = false
            } else {
                onClick()
            }
        },
        modifier = if (tooltipText != null) {
            modifier.trackGeoVaultTooltipBounds(interactionSource) { anchorBounds = it }
        } else {
            modifier
        },
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

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
    val suppressNextClickAfterTooltip = if (tooltipText != null) {
        remember { mutableStateOf(false) }
    } else {
        null
    }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = enabled,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
            suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
        )
    }
    Box(
        modifier = (if (tooltipText != null) {
            modifier.trackGeoVaultTooltipBounds(interactionSource) { anchorBounds = it }
        } else {
            modifier
        })
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    if (suppressNextClickAfterTooltip?.value == true) {
                        suppressNextClickAfterTooltip.value = false
                    } else {
                        onClick()
                    }
                },
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
    val suppressNextClickAfterTooltip = if (tooltipText != null) {
        remember { mutableStateOf(false) }
    } else {
        null
    }
    if (tooltipText != null) {
        GeoVaultInstallLongPressTooltip(
            tooltipText = tooltipText,
            enabled = true,
            interactionSource = interactionSource,
            anchorBounds = anchorBounds,
            suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
        )
    }
    FloatingActionButton(
        onClick = {
            if (suppressNextClickAfterTooltip?.value == true) {
                suppressNextClickAfterTooltip.value = false
            } else {
                onClick()
            }
        },
        modifier = if (tooltipText != null) {
            modifier.trackGeoVaultTooltipBounds(interactionSource) { anchorBounds = it }
        } else {
            modifier
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
        content = content,
    )
}

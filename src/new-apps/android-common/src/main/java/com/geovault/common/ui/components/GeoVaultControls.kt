package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Button
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.geovault.common.ui.theme.GeoVaultColorTokens
import kotlinx.coroutines.delay
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlin.math.roundToInt

data class GeoVaultButtonStyle(
    val colors: ButtonColors,
    val border: BorderStroke? = null,
)

@Composable
fun GeoVaultBaseButton(
    text: String,
    onClick: () -> Unit,
    style: GeoVaultButtonStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    fitToContent: Boolean = false,
    minWidthWhenFitToContent: Dp = 1.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
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
    val tooltipText = tooltip?.takeIf { it.isNotBlank() }
    var anchorBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

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
        if (!enabled || tooltipText == null || !isPressed) return@LaunchedEffect
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

    val baseModifier = if (fitToContent) {
        modifier.widthIn(min = minWidthWhenFitToContent)
    } else {
        modifier
    }
    val buttonModifier = baseModifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        anchorBounds = android.graphics.Rect(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
        )
        updateAnchorProxyLayout()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = buttonModifier,
        interactionSource = interactionSource,
        colors = style.colors,
        border = style.border,
        elevation = ButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp
        )
    ) {
        Text(text = text)
    }
}

@Composable
fun GeoVaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    fitToContent: Boolean = false,
) {
    GeoVaultBaseButton(
        text = text,
        onClick = onClick,
        style = GeoVaultButtonStyle(
            colors = ButtonDefaults.buttonColors(
                backgroundColor = GeoVaultColorTokens.PrimaryBlue,
                contentColor = Color.White,
                disabledBackgroundColor = GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.75f)
            ),
        ),
        modifier = modifier,
        enabled = enabled,
        tooltip = tooltip,
        fitToContent = fitToContent,
    )
}

@Composable
fun GeoVaultSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = GeoVaultColorTokens.PrimaryBlue,
    tooltip: String? = null,
    fitToContent: Boolean = false,
) {
    GeoVaultBaseButton(
        text = text,
        onClick = onClick,
        style = GeoVaultButtonStyle(
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color.Transparent,
                contentColor = accentColor,
                disabledBackgroundColor = Color.Transparent,
                disabledContentColor = accentColor.copy(alpha = 0.5f)
            ),
            border = BorderStroke(1.dp, accentColor),
        ),
        modifier = modifier,
        enabled = enabled,
        tooltip = tooltip,
        fitToContent = fitToContent,
    )
}

@Composable
fun GeoVaultCheckmark(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = GeoVaultColorTokens.PrimaryBlue,
                uncheckedColor = GeoVaultColorTokens.PrimaryBlue,
                checkmarkColor = Color.White
            )
        )
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun GeoVaultToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GeoVaultColorTokens.PrimaryBlue,
                checkedTrackColor = GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.45f),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.25f)
            )
        )
    }
}

/**
 * Shared outlined text field styled for GeoVault.
 *
 * Outside-tap keyboard dismissal is applied at the shared [com.geovault.common.ui.theme.GeoVaultTheme]
 * root, so screens using common theme wrappers get full-screen dismissal behavior automatically.
 */
@Composable
fun GeoVaultInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val fieldBackground =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.Surface
        } else {
            MaterialTheme.colors.surface
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let {
            { Text(it, color = GeoVaultColorTokens.PrimaryBlue) }
        },
        placeholder = placeholder?.let {
            { Text(it, color = GeoVaultColorTokens.TextSecondary) }
        },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            backgroundColor = fieldBackground,
            focusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            focusedLabelColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedLabelColor = GeoVaultColorTokens.PrimaryBlue
        )
    )
}

package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Button
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import androidx.compose.foundation.interaction.MutableInteractionSource

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
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    centeredContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val tooltipText = tooltip?.takeIf { it.isNotBlank() }

    val baseModifier = if (fitToContent) {
        modifier.widthIn(min = minWidthWhenFitToContent)
    } else {
        modifier
    }

    val buttonContent: @Composable RowScope.() -> Unit = if (centeredContent != null) {
        {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    centeredContent()
                }
            }
        }
    } else {
        {
            if (trailingContent != null) {
                Text(text = text, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                trailingContent()
            } else {
                Text(text = text)
            }
        }
    }

    if (tooltipText != null) {
        GeoVaultBaseButtonWithTooltip(
            text = text,
            onClick = onClick,
            style = style,
            modifier = baseModifier,
            enabled = enabled,
            tooltipText = tooltipText,
            content = buttonContent,
            contentPadding = contentPadding,
        )
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = baseModifier,
            colors = style.colors,
            border = style.border,
            elevation = ButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                disabledElevation = 0.dp,
                hoveredElevation = 0.dp,
                focusedElevation = 0.dp
            ),
            contentPadding = contentPadding,
            content = buttonContent,
        )
    }
}

@Composable
private fun GeoVaultBaseButtonWithTooltip(
    text: String,
    onClick: () -> Unit,
    style: GeoVaultButtonStyle,
    modifier: Modifier,
    enabled: Boolean,
    tooltipText: String,
    content: @Composable RowScope.() -> Unit,
    contentPadding: PaddingValues,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    GeoVaultInstallLongPressTooltip(
        tooltipText = tooltipText,
        enabled = enabled,
        interactionSource = interactionSource,
        anchorBounds = anchorBounds,
    )
    val buttonModifier = modifier.trackGeoVaultTooltipBounds { anchorBounds = it }
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
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun GeoVaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    fitToContent: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
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
        contentPadding = contentPadding,
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
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    centeredContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
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
        trailingContent = trailingContent,
        centeredContent = centeredContent,
        contentPadding = contentPadding,
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
 * root using an initial pointer pass, so taps on any control (not only blank areas) clear focus
 * and hide the IME when appropriate.
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable (() -> Unit))? = null,
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
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            backgroundColor = fieldBackground,
            focusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            focusedLabelColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedLabelColor = GeoVaultColorTokens.PrimaryBlue
        )
    )
}

data class GeoVaultSelectOption<T>(
    val value: T,
    val label: String,
)

/**
 * Shared select/dropdown control styled with [GeoVaultInput].
 *
 * Keeps selection logic in one component and avoids ad-hoc overlay/dropdown patterns per screen.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun <T> GeoVaultDropdownSelect(
    selectedValue: T,
    options: List<GeoVaultSelectOption<T>>,
    onValueSelected: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        ?: options.firstOrNull()?.label.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { requested ->
            if (enabled) {
                expanded = requested
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        GeoVaultInput(
            value = selectedLabel,
            onValueChange = {},
            label = label,
            readOnly = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onValueSelected(option.value)
                    },
                ) {
                    Text(option.label)
                }
            }
        }
    }
}

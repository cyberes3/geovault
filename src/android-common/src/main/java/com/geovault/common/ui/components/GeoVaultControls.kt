package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Button
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.graphics.Rect
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultInputLabelColor
import com.geovault.common.ui.theme.geoVaultInputPlaceholderColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor
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
    /**
     * Optional **leading** slot (e.g. icon) before [text]. The leading slot is **anchored at the
     * row's start** so its x-position does not shift when [text] / [trailingContent] change
     * (e.g. label swapped for a save-in-progress spinner). Label and trailing element render in a
     * weighted, centered area to the right of the leading slot. Mutually exclusive with
     * [centeredContent] alone; do not pass [centeredContent] together with leading/trailing.
     */
    leadingContent: (@Composable () -> Unit)? = null,
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
            when {
                leadingContent != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingContent()
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (text.isNotBlank()) {
                                Text(text = text)
                            }
                            if (trailingContent != null) {
                                if (text.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                trailingContent()
                            }
                        }
                    }
                }
                trailingContent != null -> {
                    Text(text = text, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    trailingContent()
                }
                else -> {
                    Text(text = text)
                }
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
    val suppressNextClickAfterTooltip = remember { mutableStateOf(false) }
    GeoVaultInstallLongPressTooltip(
        tooltipText = tooltipText,
        enabled = enabled,
        interactionSource = interactionSource,
        anchorBounds = anchorBounds,
        suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
    )
    val buttonModifier = modifier.trackGeoVaultTooltipBounds(interactionSource) { anchorBounds = it }
    Button(
        onClick = {
            if (suppressNextClickAfterTooltip.value) {
                suppressNextClickAfterTooltip.value = false
            } else {
                onClick()
            }
        },
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
    visuallyDisabled: Boolean = false,
    tooltip: String? = null,
    fitToContent: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    /**
     * Optional start icon shorthand before [text]. Ignored when [leadingContent] is non-null.
     * Uses [text] for [Icon] content description when [leadingIconContentDescription] is null.
     */
    leadingIcon: ImageVector? = null,
    leadingIconContentDescription: String? = null,
    /**
     * Optional custom leading slot (same contract as [GeoVaultBaseButton.leadingContent]).
     * When set, [leadingIcon] is ignored.
     */
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    centeredContent: (@Composable () -> Unit)? = null,
) {
    val resolvedBackgroundColor = if (visuallyDisabled) {
        GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f)
    } else {
        GeoVaultColorTokens.MainBlue
    }
    val resolvedContentColor = if (visuallyDisabled) {
        Color.White.copy(alpha = 0.75f)
    } else {
        Color.White
    }
    val leadingFromIcon: (@Composable () -> Unit)? = leadingIcon?.let { li ->
        {
            Icon(
                imageVector = li,
                contentDescription = leadingIconContentDescription ?: text,
            )
        }
    }
    val resolvedLeading = leadingContent ?: leadingFromIcon
    GeoVaultBaseButton(
        text = text,
        onClick = onClick,
        style = GeoVaultButtonStyle(
            colors = ButtonDefaults.buttonColors(
                backgroundColor = resolvedBackgroundColor,
                contentColor = resolvedContentColor,
                disabledBackgroundColor = GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.75f)
            ),
        ),
        modifier = modifier,
        enabled = enabled,
        tooltip = tooltip,
        fitToContent = fitToContent,
        contentPadding = contentPadding,
        leadingContent = resolvedLeading,
        trailingContent = trailingContent,
        centeredContent = centeredContent,
    )
}

/**
 * Square primary-filled icon button — the Compose equivalent of the old app's
 * `@style/gv_common_ButtonIconBase` with `iconTint=@color/surface`. Width is fixed at
 * [minSize] (the button wraps an [Icon] so it would otherwise grow to match
 * [GeoVaultBaseButton]'s full-width centered content slot); height starts at [minSize] but
 * is left as a minimum so callers can stretch it vertically inside an intrinsic-min row to
 * match the height of a sibling input/selector.
 *
 * Prefer this over the slim ripple-only [com.geovault.common.ui.components.GeoVaultIconButton]
 * when the icon should visually match a primary button sitting beside it (e.g. "+" next to a
 * selector field, as in the Import Data File screen's coordinate-system row).
 */
@Composable
fun GeoVaultFilledIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    minSize: Dp = 48.dp,
    backgroundColor: Color = GeoVaultColorTokens.MainBlue,
    contentColor: Color = Color.White,
) {
    GeoVaultBaseButton(
        text = "",
        onClick = onClick,
        style = GeoVaultButtonStyle(
            colors = ButtonDefaults.buttonColors(
                backgroundColor = backgroundColor,
                contentColor = contentColor,
                disabledBackgroundColor = backgroundColor.copy(alpha = 0.5f),
                disabledContentColor = contentColor.copy(alpha = 0.75f),
            ),
        ),
        modifier = modifier
            .width(minSize)
            .defaultMinSize(minHeight = minSize),
        enabled = enabled,
        tooltip = tooltip,
        contentPadding = PaddingValues(0.dp),
        centeredContent = {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
            )
        },
    )
}

@Composable
fun GeoVaultSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = GeoVaultColorTokens.MainBlue,
    tooltip: String? = null,
    fitToContent: Boolean = false,
    /**
     * Optional start icon (e.g. [androidx.compose.material.Icon]) before the label. Layout rules
     * are identical to [GeoVaultBaseButton] (leading anchored start, label + trailing centered
     * in the remainder, blank label + trailing for spinners, etc.).
     */
    leadingContent: (@Composable () -> Unit)? = null,
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
        leadingContent = leadingContent,
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
                checkedColor = GeoVaultColorTokens.MainBlue,
                uncheckedColor = GeoVaultColorTokens.MainBlue,
                checkmarkColor = Color.White
            )
        )
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Canonical GeoVault switch primitive. Use this anywhere you previously wrote `Switch(...)`
 * with per-call `SwitchDefaults.colors(...)` — keeps the thumb / track palette consistent
 * across SettingsScreen, filter screens, the map display dialog, and the import wizard, and
 * keeps dark-mode behaviour in one place instead of drifting per call-site.
 *
 * Tokens come from [GeoVaultColorTokens] and auto-swap on [isSystemInDarkTheme]. The palette
 * intentionally matches `GeoVaultToggleHelpCard` because it is the only one that was
 * dark-mode-aware; the earlier `GeoVaultToggle` palette (white-thumb-off, translucent-blue
 * track) was light-mode only and did not survive night mode.
 */
@Composable
fun GeoVaultSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val offThumb = if (isDark) GeoVaultColorTokens.Dark.ToggleThumbOff else GeoVaultColorTokens.ToggleThumbOff
    val offTrack = if (isDark) GeoVaultColorTokens.Dark.ToggleTrackOff else GeoVaultColorTokens.ToggleTrackOff
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = GeoVaultColorTokens.MainBlue,
            uncheckedThumbColor = offThumb,
            uncheckedTrackColor = offTrack,
            uncheckedTrackAlpha = 1f,
        ),
    )
}

@Composable
fun GeoVaultToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Optional override for the label text colour. Defaults to [Color.Unspecified] which
     * means "inherit [LocalContentColor]" (e.g. [GeoVaultInfoDialog] supplies [MaterialTheme.colors.onSurface]
     * for dialog bodies, so labels stay correct in dark mode). Pass an explicit colour only
     * for rare accent labels — do not use [Color.Black], which breaks night mode.
     */
    labelColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = labelColor)
        GeoVaultSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    val fieldBackground = geoVaultTextFieldFillColor()
    val labelColor = geoVaultInputLabelColor()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let {
            { Text(it, color = labelColor) }
        },
        placeholder = placeholder?.let { ph ->
            {
                val hintColor = geoVaultInputPlaceholderColor()
                CompositionLocalProvider(LocalContentColor provides hintColor) {
                    Text(ph, color = hintColor)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            backgroundColor = fieldBackground,
            focusedBorderColor = GeoVaultColorTokens.MainBlue,
            unfocusedBorderColor = GeoVaultColorTokens.MainBlue,
            disabledBorderColor = GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f),
            focusedLabelColor = labelColor,
            unfocusedLabelColor = labelColor,
            disabledLabelColor = labelColor,
            placeholderColor = geoVaultInputPlaceholderColor(),
            disabledPlaceholderColor = geoVaultInputPlaceholderColor().copy(alpha = 0.5f),
        )
    )
}

/**
 * Option model shared by [GeoVaultSelectField] and [GeoVaultSingleSelectDialog] (single-select)
 * and [GeoVaultMultiSelectDialog] (multi-select). [value] is the domain-level payload;
 * [label] is the display string shown to the user.
 */
data class GeoVaultSelectOption<T>(
    val value: T,
    val label: String,
)

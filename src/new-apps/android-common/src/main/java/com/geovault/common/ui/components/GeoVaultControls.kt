package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = GeoVaultColorTokens.PrimaryBlue,
            contentColor = Color.White,
            disabledBackgroundColor = GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.75f)
        ),
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
fun GeoVaultSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.White,
            contentColor = GeoVaultColorTokens.PrimaryBlue,
            disabledBackgroundColor = Color.White,
            disabledContentColor = GeoVaultColorTokens.PrimaryBlue.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
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
 * To dismiss the keyboard when the user taps outside the field, apply
 * [com.geovault.common.ui.modifier.dismissKeyboardOnOutsideTap] on a suitable ancestor
 * (for example the screen or form [Column] / [com.geovault.common.ui.components.GeoVaultFormSection]).
 * [com.geovault.common.ui.components.GeoVaultInitialAuthView] applies this automatically.
 */
@Composable
fun GeoVaultInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = GeoVaultColorTokens.PrimaryBlue) },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedBorderColor = GeoVaultColorTokens.PrimaryBlue,
            focusedLabelColor = GeoVaultColorTokens.PrimaryBlue,
            unfocusedLabelColor = GeoVaultColorTokens.PrimaryBlue
        )
    )
}

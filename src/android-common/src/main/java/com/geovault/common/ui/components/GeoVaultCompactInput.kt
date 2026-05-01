package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Compact text input style for dense rows such as drawer filters and list search headers.
 *
 * This keeps the outlined [GeoVaultInput] treatment while locking height and padding so dense
 * search rows do not jump when trailing actions appear.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun GeoVaultCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.body2,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fieldBackground =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.Surface
        } else {
            MaterialTheme.colors.surface
        }
    val colors = TextFieldDefaults.outlinedTextFieldColors(
        backgroundColor = fieldBackground,
        focusedBorderColor = GeoVaultColorTokens.MainBlue,
        unfocusedBorderColor = GeoVaultColorTokens.MainBlue,
        focusedLabelColor = GeoVaultColorTokens.MainBlue,
        unfocusedLabelColor = GeoVaultColorTokens.MainBlue,
        placeholderColor = GeoVaultColorTokens.TextSecondary,
    )
    val textColor by colors.textColor(enabled)
    val cursorColor by colors.cursorColor(isError = false)
    val mergedTextStyle = textStyle.merge(
        TextStyle(color = textStyle.color.takeOrElse { textColor })
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        modifier = modifier
            .height(44.dp)
            .background(fieldBackground, MaterialTheme.shapes.small),
        enabled = enabled,
        textStyle = mergedTextStyle,
        cursorBrush = SolidColor(cursorColor),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            TextFieldDefaults.OutlinedTextFieldDecorationBox(
                value = value,
                visualTransformation = visualTransformation,
                innerTextField = innerTextField,
                label = label?.let { labelText ->
                    {
                        Text(
                            text = labelText,
                            color = GeoVaultColorTokens.MainBlue,
                        )
                    }
                },
                placeholder = placeholder?.let { placeholderText ->
                    {
                        Text(
                            text = placeholderText,
                            style = textStyle,
                            color = GeoVaultColorTokens.TextSecondary,
                        )
                    }
                },
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                singleLine = singleLine,
                enabled = enabled,
                isError = false,
                interactionSource = interactionSource,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                border = {
                    TextFieldDefaults.BorderBox(
                        enabled = enabled,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                    )
                },
            )
        },
    )
}

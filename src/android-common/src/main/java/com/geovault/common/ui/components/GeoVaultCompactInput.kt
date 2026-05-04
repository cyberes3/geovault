package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultInputLabelColor
import com.geovault.common.ui.theme.geoVaultInputPlaceholderColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor

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
    val fieldBackground = geoVaultTextFieldFillColor()
    val labelColor = geoVaultInputLabelColor()
    val colors = TextFieldDefaults.outlinedTextFieldColors(
        backgroundColor = Color.Transparent,
        focusedBorderColor = GeoVaultColorTokens.MainBlue,
        unfocusedBorderColor = GeoVaultColorTokens.MainBlue,
        disabledBorderColor = GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f),
        focusedLabelColor = labelColor,
        unfocusedLabelColor = labelColor,
        disabledLabelColor = labelColor,
        placeholderColor = geoVaultInputPlaceholderColor(),
        disabledPlaceholderColor = geoVaultInputPlaceholderColor().copy(alpha = 0.5f),
    )
    val textColor by colors.textColor(enabled)
    val cursorColor by colors.cursorColor(isError = false)
    val mergedTextStyle = textStyle.merge(
        TextStyle(color = textStyle.color.takeOrElse { textColor })
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .background(fieldBackground, MaterialTheme.shapes.small),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth(),
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
                                color = labelColor,
                            )
                        }
                    },
                    placeholder = placeholder?.let { placeholderText ->
                        {
                            val hintColor = geoVaultInputPlaceholderColor()
                            CompositionLocalProvider(LocalContentColor provides hintColor) {
                                Text(
                                    text = placeholderText,
                                    style = textStyle,
                                    color = hintColor,
                                )
                            }
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
}

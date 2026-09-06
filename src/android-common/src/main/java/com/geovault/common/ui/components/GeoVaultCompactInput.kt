package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
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

enum class GeoVaultCompactInputStyle {
    Outlined,
    Filled,
}

/**
 * Compact text input for dense rows such as drawer filters and list search headers.
 *
 * [GeoVaultCompactInputStyle.Outlined] is the form-field treatment. [GeoVaultCompactInputStyle.Filled]
 * is the drawer search treatment (top-rounded fill, indicator line, no outline).
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun GeoVaultCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: GeoVaultCompactInputStyle = GeoVaultCompactInputStyle.Outlined,
    label: String? = null,
    placeholder: String? = null,
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
    val textColor: Color
    val cursorColor: Color
    val fieldModifier: Modifier
    val decorationBox: @Composable (@Composable () -> Unit) -> Unit

    when (style) {
        GeoVaultCompactInputStyle.Outlined -> {
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
            val resolvedTextColor by colors.textColor(enabled)
            val resolvedCursorColor by colors.cursorColor(isError = false)
            textColor = resolvedTextColor
            cursorColor = resolvedCursorColor
            fieldModifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
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
                    placeholder = compactPlaceholder(placeholder, textStyle),
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
            }
        }
        GeoVaultCompactInputStyle.Filled -> {
            val colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent,
                placeholderColor = geoVaultInputPlaceholderColor(),
                disabledPlaceholderColor = geoVaultInputPlaceholderColor().copy(alpha = 0.5f),
                focusedIndicatorColor = GeoVaultColorTokens.MainBlue,
                unfocusedIndicatorColor = GeoVaultColorTokens.MainBlue,
                disabledIndicatorColor = GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f),
            )
            val resolvedTextColor by colors.textColor(enabled)
            val resolvedCursorColor by colors.cursorColor(isError = false)
            textColor = resolvedTextColor
            cursorColor = resolvedCursorColor
            fieldModifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .indicatorLine(
                    enabled = enabled,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                )
            decorationBox = { innerTextField ->
                TextFieldDefaults.TextFieldDecorationBox(
                    value = value,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = compactPlaceholder(placeholder, textStyle),
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    singleLine = singleLine,
                    enabled = enabled,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    val mergedTextStyle = textStyle.merge(
        TextStyle(color = textStyle.color.takeOrElse { textColor }),
    )
    val backgroundShape = when (style) {
        GeoVaultCompactInputStyle.Outlined -> MaterialTheme.shapes.small
        GeoVaultCompactInputStyle.Filled -> {
            val themeSmall = MaterialTheme.shapes.small
            remember(themeSmall) {
                RoundedCornerShape(
                    topStart = themeSmall.topStart,
                    topEnd = themeSmall.topEnd,
                    bottomEnd = CornerSize(0.dp),
                    bottomStart = CornerSize(0.dp),
                )
            }
        }
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .background(fieldBackground, backgroundShape),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            modifier = fieldModifier,
            enabled = enabled,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(cursorColor),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            interactionSource = interactionSource,
            decorationBox = decorationBox,
        )
    }
}

@Composable
private fun compactPlaceholder(
    placeholder: String?,
    textStyle: TextStyle,
): (@Composable () -> Unit)? {
    if (placeholder == null) return null
    return {
        val hintColor = geoVaultInputPlaceholderColor()
        CompositionLocalProvider(LocalContentColor provides hintColor) {
            Text(
                text = placeholder,
                style = textStyle,
                color = hintColor,
            )
        }
    }
}

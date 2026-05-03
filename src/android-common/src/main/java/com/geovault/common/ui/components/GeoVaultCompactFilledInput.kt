package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldDefaults.indicatorLine
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Compact filled text input extracted from the map drawer search rows.
 *
 * This is intentionally separate from [GeoVaultInput], which is the outlined form-field style.
 * Drawer search historically used Material's filled text-field treatment; keeping that as a
 * first-class common component prevents app-local hand-rolled drawer fields from drifting while
 * allowing the internal content padding to be tightened for drawer density.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun GeoVaultCompactFilledInput(
    value: String,
    onValueChange: (String) -> Unit,
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
    val fill = geoVaultTextFieldFillColor()
    val colors = TextFieldDefaults.textFieldColors(
        backgroundColor = Color.Transparent,
    )
    val textColor by colors.textColor(enabled)
    val cursorColor by colors.cursorColor(isError = false)
    val mergedTextStyle = textStyle.merge(
        TextStyle(color = textStyle.color.takeOrElse { textColor })
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .background(fill, MaterialTheme.shapes.small),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            modifier = Modifier
                .fillMaxWidth()
                .indicatorLine(
                    enabled = enabled,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                ),
            enabled = enabled,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(cursorColor),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                TextFieldDefaults.TextFieldDecorationBox(
                    value = value,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = placeholder?.let { placeholderText ->
                        {
                            Text(
                                text = placeholderText,
                                style = textStyle,
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
                )
            },
        )
    }
}

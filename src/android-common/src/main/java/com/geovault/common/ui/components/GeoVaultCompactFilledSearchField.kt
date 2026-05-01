package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact search field using the filled drawer-input style.
 *
 * Matches the old NGS/Survey drawer search rows: no leading icon, body2 text, filled Material
 * field treatment, optional loading spinner, and a trailing clear affordance.
 */
@Composable
fun GeoVaultCompactFilledSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    showClearAction: Boolean = value.isNotEmpty(),
    onClear: (() -> Unit)? = null,
    clearContentDescription: String = "Clear search",
) {
    val clearAction = onClear ?: { onValueChange("") }
    GeoVaultCompactFilledInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        readOnly = !enabled,
        singleLine = true,
        trailingIcon = if (isLoading || showClearAction) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        GeoVaultLoadingSpinner(
                            spinnerSize = 18.dp,
                            strokeWidth = 2.dp,
                        )
                    }
                    if (showClearAction) {
                        IconButton(
                            onClick = clearAction,
                            enabled = enabled,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = clearContentDescription,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
    )
}

package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor

/**
 * Compact search field styled with [GeoVaultCompactInput]. Renders a leading search icon, no label,
 * and a trailing clear button that appears when [value] is non-empty.
 *
 * Collapses four copies of per-screen search-row boilerplate into one call.
 */
@Composable
fun GeoVaultSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search\u2026",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    showClearAction: Boolean = value.isNotEmpty(),
    onClear: (() -> Unit)? = null,
    clearContentDescription: String = "Clear",
    clearTooltip: String? = clearContentDescription,
) {
    val clearAction = onClear ?: { onValueChange("") }
    GeoVaultCompactInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = geoVaultContentSecondaryColor(),
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (isLoading || showClearAction) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        GeoVaultLoadingSpinner(
                            spinnerSize = 16.dp,
                            strokeWidth = 2.dp,
                        )
                    }
                    if (showClearAction) {
                        GeoVaultIconButton(
                            onClick = clearAction,
                            enabled = enabled,
                            tooltip = clearTooltip,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = clearContentDescription,
                                tint = geoVaultContentSecondaryColor(),
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

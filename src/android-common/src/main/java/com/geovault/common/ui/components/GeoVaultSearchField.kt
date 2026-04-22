package com.geovault.common.ui.components

import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * Outlined search field styled with [GeoVaultInput]. Renders a leading search icon, no label,
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
) {
    GeoVaultInput(
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
                tint = GeoVaultColorTokens.TextSecondary,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                GeoVaultIconButton(
                    onClick = { onValueChange("") },
                    tooltip = "Clear",
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Clear",
                        tint = GeoVaultColorTokens.TextSecondary,
                    )
                }
            }
        } else {
            null
        },
    )
}

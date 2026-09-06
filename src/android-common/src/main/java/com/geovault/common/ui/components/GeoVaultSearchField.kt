package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.R
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor

/**
 * Compact search field styled with [GeoVaultCompactInput].
 *
 * [GeoVaultCompactInputStyle.Outlined] includes a leading search icon. [GeoVaultCompactInputStyle.Filled]
 * matches drawer search rows (no leading icon, filled indicator).
 */
@Composable
fun GeoVaultSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: GeoVaultCompactInputStyle = GeoVaultCompactInputStyle.Outlined,
    placeholder: String = "Search\u2026",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    showClearAction: Boolean = value.isNotEmpty(),
    onClear: (() -> Unit)? = null,
    clearContentDescription: String = "Clear",
    clearTooltip: String? = null,
) {
    val resolvedClearTooltip = clearTooltip ?: stringResource(R.string.gv_common_search_clear_tooltip)
    val clearAction = onClear ?: { onValueChange("") }
    val spinnerSize = if (style == GeoVaultCompactInputStyle.Filled) 18.dp else 16.dp
    GeoVaultCompactInput(
        value = value,
        onValueChange = onValueChange,
        style = style,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        readOnly = style == GeoVaultCompactInputStyle.Filled && !enabled,
        singleLine = true,
        leadingIcon = if (style == GeoVaultCompactInputStyle.Outlined) {
            {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = geoVaultContentSecondaryColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        trailingIcon = if (isLoading || showClearAction) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        GeoVaultLoadingSpinner(
                            spinnerSize = spinnerSize,
                            strokeWidth = 2.dp,
                        )
                    }
                    if (showClearAction) {
                        GeoVaultIconButton(
                            onClick = clearAction,
                            enabled = enabled,
                            tooltip = resolvedClearTooltip,
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

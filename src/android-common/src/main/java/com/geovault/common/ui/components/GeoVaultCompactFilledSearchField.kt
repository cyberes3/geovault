package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.R
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor

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
    clearTooltip: String? = null,
) {
    val resolvedClearTooltip = clearTooltip ?: stringResource(R.string.gv_common_search_clear_tooltip)
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

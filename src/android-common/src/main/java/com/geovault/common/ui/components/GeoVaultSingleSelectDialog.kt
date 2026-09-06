package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogAccentButtonColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor

/**
 * Single-select popup picker: title, optional search, scrollable options, Cancel.
 *
 * Picking a row invokes [onSelected] with the chosen value; callers hide the dialog as part
 * of that callback (or use [GeoVaultSelectField], which handles dismissal).
 */
@Composable
fun <T> GeoVaultSingleSelectDialog(
    title: String,
    options: List<GeoVaultSelectOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    searchable: Boolean = false,
    searchPlaceholder: String = "Search\u2026",
    emptyLabel: String = "No options",
    cancelText: String = "Cancel",
) {
    var query by remember { mutableStateOf("") }
    val visibleOptions = remember(options, query, searchable) {
        if (searchable) filterItemsByLabel(options, query) { it.label } else options
    }
    GeoVaultPickerDialogShell(
        title = title,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        if (searchable) {
            GeoVaultSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = searchPlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (visibleOptions.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.body2,
                color = geoVaultContentSecondaryColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(
                    items = visibleOptions,
                    key = { it.label to (it.value?.hashCode() ?: 0) },
                ) { option ->
                    OptionRow(
                        option = option,
                        selected = option.value == selectedValue,
                        onClick = {
                            onSelected(option.value)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text = cancelText,
                    color = geoVaultDialogAccentButtonColor(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun <T> OptionRow(
    option: GeoVaultSelectOption<T>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isLight = MaterialTheme.colors.isLight
    val rowBackground = when {
        !selected -> geoVaultTextFieldFillColor()
        isLight -> GeoVaultColorTokens.Blue100
        else -> GeoVaultColorTokens.MainBlue.copy(alpha = 0.22f)
    }
    val textColor =
        if (selected) geoVaultDialogTitleColor() else MaterialTheme.colors.onSurface
    val rowShape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp)
            .clip(rowShape)
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.body1,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

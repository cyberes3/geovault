package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogAccentButtonColor
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor

/**
 * Case-insensitive label filter used by [GeoVaultSingleSelectDialog] when [GeoVaultSingleSelectDialog]
 * is rendered with `searchable = true`. Extracted as a top-level function so the filter behavior
 * can be unit-tested without spinning up Compose.
 */
fun <T> filterSelectOptions(
    options: List<GeoVaultSelectOption<T>>,
    query: String,
): List<GeoVaultSelectOption<T>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return options
    return options.filter { it.label.contains(trimmed, ignoreCase = true) }
}

/**
 * Single-select popup picker. Models the manholer "Pipe Size" dialog pattern: a centered modal
 * surface with a title, a scrollable list of options (the currently-selected row is tinted
 * primary-blue), and a Cancel button. An optional search field at the top filters the list by
 * label when [searchable] is `true`.
 *
 * Picking a row invokes [onSelected] with the chosen value; callers are expected to hide the
 * dialog as part of that callback (or use [GeoVaultSelectField] which handles dismissal
 * automatically).
 */
@Composable
fun <T> GeoVaultSingleSelectDialog(
    title: String,
    options: List<GeoVaultSelectOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    searchable: Boolean = false,
    searchPlaceholder: String = "Search\u2026",
    emptyLabel: String = "No options",
    cancelText: String = "Cancel",
) {
    var query by remember { mutableStateOf("") }
    val visibleOptions = remember(options, query, searchable) {
        if (searchable) filterSelectOptions(options, query) else options
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = geoVaultDialogSurfaceColor(),
            elevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                    color = geoVaultDialogTitleColor(),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
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
                                    onDismiss()
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
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = cancelText,
                            color = geoVaultDialogAccentButtonColor(),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
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
    val textColor = if (selected) GeoVaultColorTokens.MainBlue else MaterialTheme.colors.onSurface
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

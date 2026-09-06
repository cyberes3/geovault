package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
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
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogAccentButtonColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor

/**
 * Generic "pick N from a list" dialog. Presents [items] with checkboxes, bound to
 * [initialSelection]; invokes [onConfirm] with the final selection on confirm, or
 * [onDismissRequest] on cancel.
 */
@Composable
fun <T> GeoVaultMultiSelectDialog(
    title: String,
    items: List<T>,
    initialSelection: Set<T>,
    labelFor: (T) -> String,
    emptyLabel: String,
    onConfirm: (Set<T>) -> Unit,
    onDismissRequest: () -> Unit,
    confirmText: String = "Apply",
    cancelText: String = "Cancel",
    modifier: Modifier = Modifier,
    searchable: Boolean = false,
    searchPlaceholder: String = "Search\u2026",
    searchNoResultsLabel: String = "No matches",
    keyOf: (T) -> Any = { it as Any },
    selectNoneLabel: String? = null,
) {
    var selection by remember(initialSelection) { mutableStateOf(initialSelection) }
    var query by remember { mutableStateOf("") }
    val visibleItems = remember(items, query, searchable, labelFor) {
        if (searchable) {
            filterItemsByLabel(items, query, labelFor)
        } else {
            items
        }
    }
    val showSelectNone = selectNoneLabel != null && items.isNotEmpty()
    GeoVaultPickerDialogShell(
        title = title,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 8.dp,
        ),
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.body2,
                color = geoVaultContentSecondaryColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            if (searchable) {
                GeoVaultSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = searchPlaceholder,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (visibleItems.isEmpty()) {
                Text(
                    text = searchNoResultsLabel,
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
                    items(visibleItems, key = { keyOf(it) }) { item ->
                        val checked = selection.contains(item)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selection = if (checked) {
                                        selection - item
                                    } else {
                                        selection + item
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = GeoVaultColorTokens.MainBlue,
                                    uncheckedColor = GeoVaultColorTokens.Gray400,
                                ),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = labelFor(item),
                                style = MaterialTheme.typography.body1,
                                color = if (checked) {
                                    geoVaultDialogTitleColor()
                                } else {
                                    MaterialTheme.colors.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            if (showSelectNone) {
                TextButton(
                    onClick = { selection = emptySet() },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text(text = selectNoneLabel, color = geoVaultDialogAccentButtonColor())
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = cancelText, color = geoVaultDialogAccentButtonColor())
                }
                TextButton(onClick = { onConfirm(selection) }) {
                    Text(text = confirmText, color = geoVaultDialogAccentButtonColor())
                }
            }
        }
    }
}

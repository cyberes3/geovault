package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogAccentButtonColor
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor

/**
 * Case-insensitive search filter for [GeoVaultMultiSelectDialog] with `searchable = true`.
 * Same behavior as [filterSelectOptions] for options, but works on a plain list and [labelFor].
 * Top-level for unit tests without spinning up Compose.
 */
fun <T> filterItemsByLabel(
    items: List<T>,
    query: String,
    labelFor: (T) -> String,
): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return items
    }
    return items.filter { labelFor(it).contains(trimmed, ignoreCase = true) }
}

/**
 * Generic "pick N from a list" dialog. Presents [items] with checkboxes, bound to
 * [initialSelection]; invokes [onConfirm] with the final selection on "Apply", or [onDismiss]
 * on cancel.
 *
 * [labelFor] renders the display text for each item. [emptyLabel] is shown when [items] is
 * empty. When [searchable] is true, a [GeoVaultSearchField] at the top filters the list by
 * [labelFor] (same idea as [GeoVaultSingleSelectDialog]).
 *
 * [searchNoResultsLabel] is shown when the query filters out all rows but [items] is not empty.
 *
 * When [selectNoneLabel] is non-null and [items] is not empty, a button with that label is shown
 * on the same row as Cancel and Apply, aligned to the start of the dialog; it clears the in-dialog
 * selection to an empty set; the caller decides the meaning on confirm (e.g. empty may mean
 * "no restriction" in some filters).
 */
@Composable
fun <T> GeoVaultMultiSelectDialog(
    title: String,
    items: List<T>,
    initialSelection: Set<T>,
    labelFor: (T) -> String,
    emptyLabel: String,
    onConfirm: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
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
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        backgroundColor = geoVaultDialogSurfaceColor(),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            if (items.isEmpty()) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.body2,
                    color = geoVaultContentSecondaryColor(),
                )
            } else {
                Column {
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
            }
        },
        confirmButton = {
            if (showSelectNone) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { selection = emptySet() },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Text(text = selectNoneLabel, color = geoVaultDialogAccentButtonColor())
                    }
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = cancelText, color = geoVaultDialogAccentButtonColor())
                        }
                        TextButton(onClick = { onConfirm(selection) }) {
                            Text(text = confirmText, color = geoVaultDialogAccentButtonColor())
                        }
                    }
                }
            } else {
                TextButton(onClick = { onConfirm(selection) }) {
                    Text(text = confirmText, color = geoVaultDialogAccentButtonColor())
                }
            }
        },
        dismissButton = if (showSelectNone) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text(text = cancelText, color = geoVaultDialogAccentButtonColor())
                }
            }
        },
    )
}

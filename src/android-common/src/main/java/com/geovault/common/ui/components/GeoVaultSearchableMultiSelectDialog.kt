package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor

/**
 * Searchable, live-toggle multi-select dialog with bordered card rows.
 *
 * Visually distinct from [GeoVaultMultiSelectDialog] (checkbox + Apply/Cancel): this variant
 * presents each row as a bordered card that fills blue with a check when selected, dismisses
 * via a single primary "Done" button, and toggles selection live (the caller owns selection
 * state and is notified per [onToggleItem]).
 *
 * Use this when:
 *  - Selection mutates external state (e.g. a draft email list) on each tap.
 *  - Optional async loading of [items] should display a spinner.
 *  - The list is potentially long and benefits from a search/filter input.
 *
 * The caller owns [searchQuery]/[onSearchQueryChange] so search state can persist or reset
 * across dialog opens as desired.
 *
 * @param title Dialog title text.
 * @param hint Optional small caption shown below the title.
 * @param items Rows to render (already sorted/pinned by the caller).
 * @param isSelected Returns whether the given item is currently selected.
 * @param onToggleItem Invoked when a row is tapped while [enabled].
 * @param onDismiss Invoked when the dialog should close from outside dismissal.
 * @param labelFor Display label for each row.
 * @param keyOf Stable key for [LazyColumn]; defaults to identity.
 * @param matchesQuery Predicate used to filter [items] when [searchQuery] is non-blank.
 *  Defaults to case-insensitive substring match against [labelFor].
 * @param searchQuery Current search text (caller-owned).
 * @param onSearchQueryChange Called on every search text change.
 * @param searchLabel Label shown above the search field.
 * @param searchPlaceholder Placeholder text for the search field.
 * @param emptyLabel Text shown when there are no rows to display (and not loading).
 * @param isLoading When true, replaces the row list with a centered spinner.
 * @param loadingText Optional label rendered below the loading spinner.
 * @param enabled Disables search input and row taps when false (e.g. during save).
 * @param confirmText Text for the bottom primary "Done" button.
 * @param onConfirm Invoked when the "Done" button is tapped. Defaults to [onDismiss].
 */
@Composable
fun <T> GeoVaultSearchableMultiSelectDialog(
    title: String,
    items: List<T>,
    isSelected: (T) -> Boolean,
    onToggleItem: (T) -> Unit,
    onDismiss: () -> Unit,
    labelFor: (T) -> String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    emptyLabel: String,
    confirmText: String,
    hint: String? = null,
    keyOf: (T) -> Any = { it as Any },
    matchesQuery: (T, String) -> Boolean = { item, q ->
        labelFor(item).contains(q, ignoreCase = true)
    },
    searchLabel: String? = null,
    searchPlaceholder: String? = null,
    isLoading: Boolean = false,
    loadingText: String? = null,
    enabled: Boolean = true,
    onConfirm: () -> Unit = onDismiss,
) {
    val trimmedQuery = searchQuery.trim()
    val visibleItems = if (trimmedQuery.isEmpty()) {
        items
    } else {
        items.filter { matchesQuery(it, trimmedQuery) }
    }

    Dialog(onDismissRequest = onDismiss) {
        val pickerWidth = LocalConfiguration.current.screenWidthDp.dp * 0.8f
        Card(
            modifier = Modifier.width(pickerWidth),
            elevation = 0.dp,
            backgroundColor = geoVaultDialogSurfaceColor(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.caption,
                        color = geoVaultContentSecondaryColor(),
                    )
                }
                GeoVaultSearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = searchPlaceholder ?: searchLabel ?: "Search...",
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GeoVaultLoadingSpinner(bottomText = loadingText)
                        }
                    }
                    visibleItems.isEmpty() -> {
                        Text(
                            text = emptyLabel,
                            style = MaterialTheme.typography.body2,
                            color = geoVaultContentSecondaryColor(),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleItems, key = { keyOf(it) }) { item ->
                                GeoVaultSearchableMultiSelectRow(
                                    label = labelFor(item),
                                    selected = isSelected(item),
                                    enabled = enabled,
                                    onClick = { onToggleItem(item) },
                                )
                            }
                        }
                    }
                }
                GeoVaultPrimaryButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GeoVaultSearchableMultiSelectRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isDark = !MaterialTheme.colors.isLight
    val rowBackground = when {
        !selected -> geoVaultTextFieldFillColor()
        isDark -> GeoVaultColorTokens.MainBlue.copy(alpha = 0.22f)
        else -> GeoVaultColorTokens.Blue100
    }
    val labelColor = if (selected) {
        GeoVaultColorTokens.MainBlue
    } else {
        MaterialTheme.colors.onSurface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = rowBackground,
        border = BorderStroke(1.dp, GeoVaultColorTokens.MainBlue),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = labelColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = GeoVaultColorTokens.MainBlue,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(20.dp))
            }
        }
    }
}

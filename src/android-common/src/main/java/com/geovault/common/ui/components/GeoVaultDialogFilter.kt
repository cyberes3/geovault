package com.geovault.common.ui.components

/**
 * Case-insensitive label substring filter used by picker dialogs.
 *
 * Blank or whitespace-only [query] returns [items] unchanged. Extracted so filter behavior can
 * be unit-tested without spinning up Compose.
 */
fun <T> filterItemsByLabel(
    items: List<T>,
    query: String,
    labelFor: (T) -> String,
): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return items
    return items.filter { labelFor(it).contains(trimmed, ignoreCase = true) }
}

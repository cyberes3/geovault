package com.geovault.common.maps.ui.scaffold

import androidx.compose.runtime.Stable

/**
 * Controlled state for [GeoVaultMapScaffold]'s built-in drawer search bar.
 *
 * The scaffold owns only the search chrome. Feature modules own the query state and decide how
 * that query filters their domain rows.
 */
@Stable
data class GeoVaultMapDrawerSearchState(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val onClear: () -> Unit,
    val placeholder: String,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
    val showClearAction: Boolean = query.isNotEmpty(),
    val clearContentDescription: String = "Clear search",
    val clearTooltip: String? = null,
)

package com.geovault.common.maps.ui.geocoding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.maps.R
import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.common.maps.geocoding.GeocodingRepository
import com.geovault.common.ui.components.GeoVaultFormDialog
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class GeoVaultMapSearchLocalResult(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * Modal search over optional local results plus the server geocoding API.
 *
 * Local search runs on [Dispatchers.Default]. Geocode failures are shown as an error, not
 * as an empty-results list.
 */
@Composable
fun GeoVaultMapGeocodeSearchDialog(
    visible: Boolean,
    repository: GeocodingRepository,
    onDismissRequest: () -> Unit,
    onPickResult: (GeocodeSearchResult) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    placeholder: String? = null,
    localSectionTitle: String? = null,
    searchLocal: (suspend (String) -> List<GeoVaultMapSearchLocalResult>)? = null,
    localSearchGeneration: Any? = null,
    onPickLocal: ((GeoVaultMapSearchLocalResult) -> Unit)? = null,
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var localResults by remember { mutableStateOf<List<GeoVaultMapSearchLocalResult>>(emptyList()) }
    var geocodeResults by remember { mutableStateOf<List<GeocodeSearchResult>>(emptyList()) }
    var geocodeError by remember { mutableStateOf(false) }
    var isSearchingLocal by remember { mutableStateOf(false) }
    var isSearchingGeocode by remember { mutableStateOf(false) }
    val searchFieldFocusRequester = remember { FocusRequester() }
    val dialogTitle = title ?: stringResource(R.string.gv_common_geocode_search_dialog_title)
    val searchPlaceholder = placeholder
        ?: stringResource(R.string.gv_common_geocode_search_placeholder)
    val placesTitle = stringResource(R.string.gv_common_geocode_search_places_section)
    val resolvedLocalTitle = localSectionTitle
        ?: stringResource(R.string.gv_common_geocode_search_local_section)

    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            localResults = emptyList()
            geocodeResults = emptyList()
            geocodeError = false
            isSearchingLocal = false
            isSearchingGeocode = false
            withFrameNanos { }
            searchFieldFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(query, visible, localSearchGeneration, searchLocal) {
        if (!visible) return@LaunchedEffect
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            localResults = emptyList()
            isSearchingLocal = false
            return@LaunchedEffect
        }
        val localSearch = searchLocal ?: run {
            localResults = emptyList()
            isSearchingLocal = false
            return@LaunchedEffect
        }
        delay(280)
        if (query.trim() != trimmed) return@LaunchedEffect
        isSearchingLocal = true
        val found = withContext(Dispatchers.Default) { localSearch(trimmed) }
        if (query.trim() != trimmed) {
            isSearchingLocal = false
            return@LaunchedEffect
        }
        localResults = found
        isSearchingLocal = false
    }

    LaunchedEffect(query, visible) {
        if (!visible) return@LaunchedEffect
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            geocodeResults = emptyList()
            geocodeError = false
            isSearchingGeocode = false
            return@LaunchedEffect
        }
        delay(280)
        if (query.trim() != trimmed) return@LaunchedEffect
        isSearchingGeocode = true
        geocodeError = false
        val response = repository.search(trimmed)
        if (query.trim() != trimmed) {
            isSearchingGeocode = false
            return@LaunchedEffect
        }
        response.fold(
            onSuccess = { found ->
                geocodeResults = found
                geocodeError = false
            },
            onFailure = {
                geocodeResults = emptyList()
                geocodeError = true
            },
        )
        isSearchingGeocode = false
    }

    GeoVaultFormDialog(
        modifier = modifier,
        title = dialogTitle,
        onConfirm = onDismissRequest,
        onDismissRequest = onDismissRequest,
        confirmText = stringResource(R.string.gv_common_geocode_search_done),
        showDismissButton = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        ) {
            GeoVaultSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFieldFocusRequester),
                placeholder = searchPlaceholder,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val trimmedQuery = query.trim()
                val hasAnyResults = localResults.isNotEmpty() || geocodeResults.isNotEmpty()
                val isSearching = isSearchingLocal || isSearchingGeocode
                when {
                    trimmedQuery.isEmpty() -> Unit
                    !hasAnyResults && isSearching -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            GeoVaultLoadingSpinner(spinnerSize = 18.dp)
                        }
                    }
                    !hasAnyResults && geocodeError -> {
                        Text(
                            text = stringResource(R.string.gv_common_geocode_search_error),
                            style = MaterialTheme.typography.body2,
                            color = geoVaultContentSecondaryColor(),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    !hasAnyResults -> {
                        Text(
                            text = stringResource(R.string.gv_common_geocode_search_empty_no_results),
                            style = MaterialTheme.typography.body2,
                            color = geoVaultContentSecondaryColor(),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 8.dp),
                        ) {
                            if (localResults.isNotEmpty()) {
                                item("local-header") {
                                    SearchSectionHeader(resolvedLocalTitle)
                                }
                                items(
                                    items = localResults,
                                    key = { item -> "local:${item.id}" },
                                ) { item ->
                                    GeoVaultMapSearchLocalResultRow(
                                        result = item,
                                        onClick = {
                                            onPickLocal?.invoke(item)
                                            onDismissRequest()
                                        },
                                    )
                                    SearchResultDivider()
                                }
                            }
                            if (geocodeResults.isNotEmpty()) {
                                item("places-header") {
                                    SearchSectionHeader(
                                        text = placesTitle,
                                        topPadding = if (localResults.isNotEmpty()) 12.dp else 0.dp,
                                    )
                                }
                                itemsIndexed(
                                    items = geocodeResults,
                                    key = { index, result ->
                                        "place:$index:${result.place_name ?: result.text}"
                                    },
                                ) { _, result ->
                                    GeoVaultGeocodeSearchResultRow(
                                        result = result,
                                        onClick = {
                                            onPickResult(result)
                                            onDismissRequest()
                                        },
                                    )
                                    SearchResultDivider()
                                }
                            }
                            if (geocodeError && geocodeResults.isEmpty()) {
                                item("places-error") {
                                    Text(
                                        text = stringResource(R.string.gv_common_geocode_search_error),
                                        style = MaterialTheme.typography.body2,
                                        color = geoVaultContentSecondaryColor(),
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            } else if (isSearchingGeocode && geocodeResults.isEmpty()) {
                                item("places-spinner") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        GeoVaultLoadingSpinner(spinnerSize = 16.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    text: String,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = geoVaultContentSecondaryColor(),
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp),
    )
}

@Composable
private fun SearchResultDivider() {
    Divider(
        color = if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.BorderLight
        } else {
            GeoVaultColorTokens.Dark.BorderLight
        },
    )
}

@Composable
fun GeoVaultMapSearchLocalResultRow(
    result: GeoVaultMapSearchLocalResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            text = result.title,
            style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!result.subtitle.isNullOrBlank()) {
            Text(
                text = result.subtitle,
                style = MaterialTheme.typography.body2,
                color = geoVaultContentSecondaryColor(),
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun GeoVaultGeocodeSearchResultRow(
    result: GeocodeSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = result.text ?: result.place_name.orEmpty()
    val subtitle = result.place_name?.takeIf { it != result.text }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.body2,
                color = geoVaultContentSecondaryColor(),
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

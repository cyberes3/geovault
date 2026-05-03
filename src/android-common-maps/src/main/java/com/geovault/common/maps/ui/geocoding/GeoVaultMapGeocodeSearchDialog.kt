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
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import kotlinx.coroutines.delay

/**
 * Modal search over the server geocoding API: debounced query, list of top results, pick to dismiss.
 *
 * Search state lives inside the dialog; callers supply [repository] and handle [onPickResult].
 */
@Composable
fun GeoVaultMapGeocodeSearchDialog(
    visible: Boolean,
    repository: GeocodingRepository,
    onDismiss: () -> Unit,
    onPickResult: (GeocodeSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val searchFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            results = emptyList()
            isSearching = false
            // Wait one frame so the field is laid out and attached before requesting focus —
            // requesting on the same frame the dialog mounts silently no-ops on the IME.
            withFrameNanos { }
            searchFieldFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(query, visible) {
        if (!visible) return@LaunchedEffect
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(280)
        if (query.trim() != trimmed) return@LaunchedEffect
        isSearching = true
        val response = repository.search(trimmed)
        if (query.trim() != trimmed) {
            isSearching = false
            return@LaunchedEffect
        }
        results = response.getOrElse { emptyList() }
        isSearching = false
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        backgroundColor = geoVaultDialogSurfaceColor(),
        title = {
            Text(
                text = stringResource(R.string.gv_common_geocode_search_dialog_title),
                style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colors.onSurface,
            )
        },
        text = {
            // Fixed-height container so the dialog reserves space for results from the moment
            // it opens — keyboard appearing + first results no longer cause it to grow/shift.
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
                    placeholder = stringResource(R.string.gv_common_geocode_search_placeholder),
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isSearching -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                GeoVaultLoadingSpinner(spinnerSize = 18.dp)
                            }
                        }
                        query.trim().isEmpty() -> Unit
                        results.isEmpty() -> {
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
                                items(
                                    count = results.size,
                                    key = { index -> index },
                                ) { index ->
                                    val item = results[index]
                                    GeoVaultGeocodeSearchResultRow(
                                        result = item,
                                        onClick = {
                                            onPickResult(item)
                                            onDismiss()
                                        },
                                    )
                                    Divider(
                                        color = if (MaterialTheme.colors.isLight) {
                                            GeoVaultColorTokens.BorderLight
                                        } else {
                                            GeoVaultColorTokens.Dark.BorderLight
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
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.gv_common_geocode_search_done),
                    color = GeoVaultColorTokens.MainBlue,
                )
            }
        },
    )
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

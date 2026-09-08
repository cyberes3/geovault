package com.geovault.places.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.geo.CoordinateFormat
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultEmptyState
import com.geovault.common.ui.components.GeoVaultFloatingActionButtonWithTooltip
import com.geovault.common.ui.components.GeoVaultLoadingOverlay
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.TopBarMenuEntry
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultListCardChrome
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultListCardChrome
import com.geovault.places.R
import com.geovault.places.model.Place
import com.geovault.places.presentation.PlacesShellState

private const val HEADER_WAITING_TO_SYNC = "WAITING TO SYNC"
private const val HEADER_PLACES = "Places"

private enum class ListSectionHeaderPlacement {
    First,
    AfterOtherSection,
}

private sealed interface PlaceListItem {
    data class Header(val title: String, val placement: ListSectionHeaderPlacement) : PlaceListItem
    data class Row(val place: Place) : PlaceListItem
}

@Composable
fun MainScreen(
    state: PlacesShellState,
    auth: GeoVaultAuthShellState,
    onSearchChanged: (String) -> Unit,
    onOpenShare: () -> Unit,
    onRefresh: () -> Unit,
    onAddPlace: () -> Unit,
    onEditPlace: (Place) -> Unit,
    onNavigatePlace: (Place) -> Unit,
    onViewDescription: (Place) -> Unit,
    onOpenMapToPlace: (Place) -> Unit,
    onCopyCoordinates: (String) -> Unit,
    onCancelRefresh: () -> Unit,
) {
    val listState = rememberLazyListState()
    val showSearchDivider by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    GeoVaultTabShell(
        title = stringResource(R.string.app_title_bar),
        auth = auth,
        subtitle = state.lastSyncLabel,
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        extraTopBarEntries = listOf(
            TopBarMenuEntry(label = "Share", onClick = onOpenShare),
        ),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchBlock(
                    query = state.searchQuery,
                    onSearchChanged = onSearchChanged,
                )
                if (showSearchDivider) {
                    Divider(
                        modifier = Modifier.fillMaxWidth(),
                        color = geoVaultHairlineDividerColor(),
                        thickness = 1.dp,
                    )
                }
                PlacesBody(
                    state = state,
                    listState = listState,
                    onRefresh = onRefresh,
                    onNavigatePlace = onNavigatePlace,
                    onEditPlace = onEditPlace,
                    onViewDescription = onViewDescription,
                    onOpenMapToPlace = onOpenMapToPlace,
                    onCopyCoordinates = onCopyCoordinates,
                )
            }
        },
        authenticatedFloatingAction = {
            FabStack(
                modifier = Modifier.align(Alignment.BottomEnd),
                onAddPlace = onAddPlace,
                enabled = !state.isRefreshing,
            )
        },
        tabOverlay = {
            GeoVaultLoadingOverlay(
                isVisible = state.showSyncOverlay,
                title = state.syncOverlayTitle,
                subtext = state.syncOverlaySubtext,
                onTap = onCancelRefresh,
            )
        },
    )
}

@Composable
private fun SearchBlock(
    query: String,
    onSearchChanged: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.background)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GeoVaultSearchField(
            value = query,
            onValueChange = onSearchChanged,
            placeholder = "Search places...",
            modifier = Modifier.fillMaxWidth(),
            clearContentDescription = "Clear search",
        )
    }
}

@Composable
private fun PlacesBody(
    state: PlacesShellState,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onNavigatePlace: (Place) -> Unit,
    onEditPlace: (Place) -> Unit,
    onViewDescription: (Place) -> Unit,
    onOpenMapToPlace: (Place) -> Unit,
    onCopyCoordinates: (String) -> Unit,
) {
    val listItems = remember(state.sections) {
        buildList {
            if (state.sections.waitingToSync.isNotEmpty()) {
                add(
                    PlaceListItem.Header(
                        title = HEADER_WAITING_TO_SYNC,
                        placement = ListSectionHeaderPlacement.First,
                    ),
                )
                state.sections.waitingToSync.forEach { place ->
                    add(PlaceListItem.Row(place))
                }
            }
            if (state.sections.saved.isNotEmpty()) {
                if (state.sections.waitingToSync.isNotEmpty()) {
                    add(
                        PlaceListItem.Header(
                            title = HEADER_PLACES,
                            placement = ListSectionHeaderPlacement.AfterOtherSection,
                        ),
                    )
                }
                state.sections.saved.forEach { place ->
                    add(PlaceListItem.Row(place))
                }
            }
        }
    }
    val selectedIndex = remember(state.selectedKey, listItems) {
        val selectedKey = state.selectedKey ?: return@remember -1
        listItems.indexOfFirst { listItem ->
            listItem is PlaceListItem.Row && listItem.place.key == selectedKey
        }
    }
    LaunchedEffect(selectedIndex, state.selectedKey) {
        if (state.selectedKey != null && selectedIndex >= 0) {
            listState.animateScrollToItem(index = selectedIndex)
        }
    }

    GeoVaultPullRefreshLoadingContainer(
        refreshing = state.isRefreshing,
        showBlockingLoader = state.isRefreshing,
        onRefresh = onRefresh,
        canRefresh = !state.isRefreshing,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
    ) {
        if (state.sections.waitingToSync.isEmpty() && state.sections.saved.isEmpty()) {
            GeoVaultEmptyState(
                icon = Icons.Default.Map,
                title = "No places",
                message = "Tap the + button to add your first place",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 8.dp),
            ) {
                items(listItems, key = { item ->
                    when (item) {
                        is PlaceListItem.Header -> item.title
                        is PlaceListItem.Row -> item.place.key.value
                    }
                }) { item ->
                    when (item) {
                        is PlaceListItem.Header ->
                            SectionHeader(text = item.title, placement = item.placement)
                        is PlaceListItem.Row -> PlaceRow(
                            place = item.place,
                            isSelected = item.place.key == state.selectedKey,
                            actionsEnabled = !state.isRefreshing,
                            onNavigatePlace = onNavigatePlace,
                            onEditPlace = onEditPlace,
                            onViewDescription = onViewDescription,
                            onOpenMapToPlace = onOpenMapToPlace,
                            onCopyCoordinates = onCopyCoordinates,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, placement: ListSectionHeaderPlacement) {
    val topPadding = when (placement) {
        ListSectionHeaderPlacement.First -> 32.dp
        ListSectionHeaderPlacement.AfterOtherSection -> 24.dp
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = topPadding, end = 4.dp, bottom = 8.dp),
        color = geoVaultContentSecondaryColor(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun PlaceRow(
    place: Place,
    isSelected: Boolean,
    actionsEnabled: Boolean,
    onNavigatePlace: (Place) -> Unit,
    onEditPlace: (Place) -> Unit,
    onViewDescription: (Place) -> Unit,
    onOpenMapToPlace: (Place) -> Unit,
    onCopyCoordinates: (String) -> Unit,
) {
    val copyCoordinates = CoordinateFormat.DECIMAL_4.formatLatLon(place.content.location.asWgs84())
    val addressOrCoordinates = place.content.address?.takeIf { it.isNotBlank() } ?: copyCoordinates
    val rawDate = place.createdAt.orEmpty()
    val formattedDate = if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate
    val dateLabel = if (place.isPending) "$formattedDate (offline)" else formattedDate

    val cardShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            .geoVaultListCardChrome(
                selected = isSelected,
                offline = place.isPending,
                shape = cardShape,
                strokeWidth = GeoVaultListCardChrome.EmphasisStrokeWidth,
            )
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable(enabled = actionsEnabled) { onOpenMapToPlace(place) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Map,
                    contentDescription = "Open map",
                    tint = GeoVaultColorTokens.MainBlue,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = place.content.name.ifBlank { "Unnamed Place" },
                color = MaterialTheme.colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dateLabel,
                color = if (place.isPending) {
                    GeoVaultColorTokens.MainYellow
                } else {
                    geoVaultContentSecondaryColor().copy(alpha = 0.8f)
                },
                fontSize = 12.sp,
            )
            Text(
                text = " • ",
                color = geoVaultContentSecondaryColor().copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
            Text(
                text = addressOrCoordinates,
                modifier = Modifier
                    .clickable(enabled = copyCoordinates.isNotBlank()) {
                        if (!actionsEnabled) return@clickable
                        onCopyCoordinates(copyCoordinates)
                    }
                    .padding(2.dp),
                color = geoVaultContentSecondaryColor(),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        place.content.description.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .clickable { onViewDescription(place) },
                color = geoVaultContentSecondaryColor(),
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GeoVaultPrimaryButton(
                text = "Navigate",
                onClick = { onNavigatePlace(place) },
                tooltip = stringResource(R.string.tooltip_place_navigate),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = actionsEnabled,
            )
            GeoVaultSecondaryButton(
                text = "Edit",
                tooltip = stringResource(R.string.tooltip_place_edit),
                onClick = { onEditPlace(place) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = actionsEnabled,
            )
        }
    }
}

@Composable
private fun FabStack(
    modifier: Modifier = Modifier,
    onAddPlace: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.padding(end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        GeoVaultFloatingActionButtonWithTooltip(
            onClick = { if (enabled) onAddPlace() },
            tooltip = "Create a new place",
            backgroundColor = GeoVaultColorTokens.MainBlue,
            contentColor = GeoVaultColorTokens.White,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Place")
        }
    }
}

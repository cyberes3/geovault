package com.geovault.places.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingOverlay
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.snackbar.GeoVaultSnackbarHost
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.places.model.Feature
import com.geovault.places.model.OfflineFeature
import com.geovault.places.presentation.MainScreenState

private const val HEADER_WAITING_TO_SYNC = "WAITING TO SYNC"
private const val HEADER_PLACES = "Places"

private enum class ListSectionHeaderPlacement {
    /** First section in the list (e.g. waiting to sync at top). */
    First,
    /** Section after another block; extra top margin separates groups. */
    AfterOtherSection,
}

private sealed interface PlaceListItem {
    data class Header(val title: String, val placement: ListSectionHeaderPlacement) : PlaceListItem
    data class Row(
        val feature: Feature,
        val isOffline: Boolean,
        val offlineFeature: OfflineFeature? = null,
        val offlineIndex: Int = -1,
    ) : PlaceListItem
}

@Composable
fun MainScreen(
    state: MainScreenState,
    onSearchChanged: (String) -> Unit,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onAddPlace: () -> Unit,
    onEditSavedPlace: (Feature) -> Unit,
    onEditOfflinePlace: (OfflineFeature, Int) -> Unit,
    onNavigatePlace: (Feature) -> Unit,
    onViewDescription: (Feature) -> Unit,
    onOpenMapToPlace: (Feature) -> Unit,
    onCopyCoordinates: (String) -> Unit,
    onCancelRefresh: () -> Unit,
    onDismissSnackbar: () -> Unit,
) {
    val listState = rememberLazyListState()
    val showSearchDivider by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "Places",
                subtitle = state.lastSyncLabel,
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        isAuthenticated = state.isAuthenticated
                    )
                }
            )
        }
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(GeoVaultColorTokens.Background)
        ) {
            GeoVaultAuthGate(
                isAuthenticated = state.isAuthenticated,
                serverUrl = state.serverUrl,
                onServerUrlChanged = onAuthServerUrlChanged,
                onConnect = onAuthConnect,
                isConnecting = state.isConnecting,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    SearchBlock(
                        query = state.searchQuery,
                        onSearchChanged = onSearchChanged
                    )
                    if (showSearchDivider) {
                        Divider(
                            modifier = Modifier.fillMaxWidth(),
                            color = GeoVaultColorTokens.BorderLight,
                            thickness = 1.dp
                        )
                    }
                    PlacesBody(
                        state = state,
                        listState = listState,
                        onRefresh = onRefresh,
                        onNavigatePlace = onNavigatePlace,
                        onEditSavedPlace = onEditSavedPlace,
                        onEditOfflinePlace = onEditOfflinePlace,
                        onViewDescription = onViewDescription,
                        onOpenMapToPlace = onOpenMapToPlace,
                        onCopyCoordinates = onCopyCoordinates,
                    )
                }
            }

            if (state.isAuthenticated) {
                FabStack(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onAddPlace = onAddPlace,
                    enabled = !state.isRefreshing
                )
            }

            GeoVaultLoadingOverlay(
                isVisible = state.showSyncOverlay,
                title = state.syncOverlayTitle,
                subtext = state.syncOverlaySubtext,
                onTap = onCancelRefresh
            )
        }
    }

    GeoVaultSnackbarHost(
        model = state.snackbar,
        onDismiss = onDismissSnackbar,
        onAction = { _ -> onDismissSnackbar() },
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
            .background(GeoVaultColorTokens.Background)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GeoVaultInput(
                value = query,
                onValueChange = onSearchChanged,
                label = null,
                placeholder = "Search places...",
                modifier = Modifier.fillMaxWidth()
            )
            if (query.isNotBlank()) {
                IconButton(
                    onClick = { onSearchChanged("") },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = GeoVaultColorTokens.TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun PlacesBody(
    state: MainScreenState,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onNavigatePlace: (Feature) -> Unit,
    onEditSavedPlace: (Feature) -> Unit,
    onEditOfflinePlace: (OfflineFeature, Int) -> Unit,
    onViewDescription: (Feature) -> Unit,
    onOpenMapToPlace: (Feature) -> Unit,
    onCopyCoordinates: (String) -> Unit,
) {
    val listItems = remember(state.saved, state.offlineItems) {
        buildList {
            if (state.offlineItems.isNotEmpty()) {
                add(
                    PlaceListItem.Header(
                        title = HEADER_WAITING_TO_SYNC,
                        placement = ListSectionHeaderPlacement.First,
                    )
                )
                state.offlineItems.forEachIndexed { index, offline ->
                    add(
                        PlaceListItem.Row(
                            feature = offline.feature,
                            isOffline = true,
                            offlineFeature = offline,
                            offlineIndex = index
                        )
                    )
                }
            }
            if (state.saved.isNotEmpty()) {
                if (state.offlineItems.isNotEmpty()) {
                    add(
                        PlaceListItem.Header(
                            title = HEADER_PLACES,
                            placement = ListSectionHeaderPlacement.AfterOtherSection,
                        )
                    )
                }
                state.saved.forEach { feature ->
                    add(PlaceListItem.Row(feature = feature, isOffline = false))
                }
            }
        }
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isRefreshing,
        onRefresh = onRefresh
    )
    val selectedIndex = remember(state.selectedPlaceId, listItems) {
        val selectedId = state.selectedPlaceId ?: return@remember -1
        listItems.indexOfFirst { listItem ->
            listItem is PlaceListItem.Row && listItem.feature.properties.database_id == selectedId
        }
    }
    LaunchedEffect(selectedIndex, state.selectedPlaceId) {
        if (state.selectedPlaceId != null && selectedIndex >= 0) {
            listState.animateScrollToItem(index = selectedIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GeoVaultColorTokens.Background)
            .pullRefresh(pullRefreshState)
    ) {
        if (state.saved.isEmpty() && state.offlineItems.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 8.dp)
            ) {
                items(listItems) { item ->
                    when (item) {
                        is PlaceListItem.Header ->
                            SectionHeader(text = item.title, placement = item.placement)
                        is PlaceListItem.Row -> PlaceRow(
                            item = item,
                            isSelected = item.feature.properties.database_id != null &&
                                item.feature.properties.database_id == state.selectedPlaceId,
                            actionsEnabled = !state.isRefreshing,
                            onNavigatePlace = onNavigatePlace,
                            onEditSavedPlace = onEditSavedPlace,
                            onEditOfflinePlace = onEditOfflinePlace,
                            onViewDescription = onViewDescription,
                            onOpenMapToPlace = onOpenMapToPlace,
                            onCopyCoordinates = onCopyCoordinates,
                        )
                    }
                }
            }
        }
        PullRefreshIndicator(
            refreshing = state.isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
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
        color = GeoVaultColorTokens.TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun PlaceRow(
    item: PlaceListItem.Row,
    isSelected: Boolean,
    actionsEnabled: Boolean,
    onNavigatePlace: (Feature) -> Unit,
    onEditSavedPlace: (Feature) -> Unit,
    onEditOfflinePlace: (OfflineFeature, Int) -> Unit,
    onViewDescription: (Feature) -> Unit,
    onOpenMapToPlace: (Feature) -> Unit,
    onCopyCoordinates: (String) -> Unit,
) {
    val feature = item.feature
    val addressOrCoordinates = feature.properties.address?.takeIf { it.isNotBlank() } ?: run {
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            String.format("%.6f, %.6f", coords[1], coords[0])
        } else {
            ""
        }
    }
    val rawDate = feature.properties.created_at.orEmpty()
    val formattedDate = if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate
    val dateLabel = if (item.isOffline) "$formattedDate (offline)" else formattedDate

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        backgroundColor = GeoVaultColorTokens.Surface,
        elevation = 0.dp,
        border = BorderStroke(
            width = 2.dp,
            color = when {
                isSelected -> GeoVaultColorTokens.PrimaryBlue
                item.isOffline -> GeoVaultColorTokens.MainYellow
                else -> GeoVaultColorTokens.PrimaryBlue
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        isSelected -> GeoVaultColorTokens.Purple100
                        else -> Color.Transparent
                    }
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(enabled = actionsEnabled) { onOpenMapToPlace(feature) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Map,
                        contentDescription = "Open map",
                        tint = GeoVaultColorTokens.PrimaryBlue
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = feature.properties.name ?: "Unnamed Place",
                    color = GeoVaultColorTokens.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateLabel,
                    color = if (item.isOffline) {
                        GeoVaultColorTokens.MainYellow
                    } else {
                        GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f)
                    },
                    fontSize = 12.sp
                )
                Text(
                    text = " • ",
                    color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
                Text(
                    text = addressOrCoordinates,
                    modifier = Modifier
                        .clickable(enabled = addressOrCoordinates.isNotBlank()) {
                            if (!actionsEnabled) return@clickable
                            onCopyCoordinates(addressOrCoordinates)
                        }
                        .padding(2.dp),
                    color = GeoVaultColorTokens.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            feature.properties.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                        .clickable { onViewDescription(feature) },
                    color = GeoVaultColorTokens.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = "Navigate",
                    onClick = { onNavigatePlace(feature) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = actionsEnabled
                )
                GeoVaultSecondaryButton(
                    text = "Edit",
                    onClick = {
                        if (item.isOffline && item.offlineFeature != null) {
                            onEditOfflinePlace(item.offlineFeature, item.offlineIndex)
                        } else {
                            onEditSavedPlace(feature)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = actionsEnabled
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Map,
            contentDescription = null,
            tint = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.2f),
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = "No places",
            modifier = Modifier.padding(top = 16.dp),
            color = GeoVaultColorTokens.TextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Tap the + button to add your first place",
            modifier = Modifier.padding(top = 8.dp),
            color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun FabStack(
    modifier: Modifier = Modifier,
    onAddPlace: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .padding(end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End
    ) {
        FloatingActionButton(
            onClick = { if (enabled) onAddPlace() },
            shape = CircleShape,
            backgroundColor = GeoVaultColorTokens.PrimaryBlue,
            contentColor = Color.White,
            elevation = androidx.compose.material.FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Place")
        }
    }
}


package com.geovault.tracker.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import com.geovault.common.ui.components.GeoVaultIconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.NaturalSort
import com.geovault.common.ui.components.GeoVaultAddRemoveRowFlags
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsHidden
import com.geovault.common.ui.components.GeoVaultSearchField
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTabBar
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultCardBorderColor
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.GroupReshareAddabilityPolicy
import kotlinx.coroutines.launch

private enum class PickerPhase { LIST, ADD }

private data class TrackerRowItem(
    val trackerId: String,
    val tracker: Tracker?,
) {
    val displayName: String get() = tracker?.name ?: trackerId
    val ownerEmail: String get() = tracker?.owner_email?.takeIf { it.isNotBlank() } ?: ""
    val isOwned: Boolean get() = tracker?.isOwner() == true
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupTrackerPickerScreen(
    groupId: String,
    groupName: String,
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    addingTrackerIds: Set<String> = emptySet(),
    onRefreshTrackers: () -> Unit = {},
    onSelectionChanged: (Set<String>) -> Unit,
    onAddTracker: (String) -> Unit = {},
    onIneligibleTrackerTap: (Tracker) -> Unit = {},
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onLeaveComposition: (() -> Unit)? = null,
    dismissOnHostInactive: Boolean = true,
    doneButtonLabel: String = stringResource(R.string.trackers_edit_pick_users_done),
) {
    GeoVaultRequestBottomTabsHidden(shouldHide = true)

    val tabs = remember {
        listOf(
            GeoVaultTab(value = PickerPhase.LIST, label = ""),
            GeoVaultTab(value = PickerPhase.ADD, label = ""),
        )
    }
    val localizedTabs = remember(tabs) {
        tabs
    }.let {
        listOf(
            it[0].copy(label = stringResource(R.string.trackers_subtab_trackers)),
            it[1].copy(label = stringResource(R.string.groups_tracker_add_title)),
        )
    }

    PickerTabContent(
        groupId = groupId,
        modifier = modifier,
        allTrackers = allTrackers,
        selectedTrackerIds = selectedTrackerIds,
        tabs = localizedTabs,
        isLoading = isLoading,
        addingTrackerIds = addingTrackerIds,
        doneButtonLabel = doneButtonLabel,
        onRemoveTracker = { id -> onSelectionChanged(selectedTrackerIds - id) },
        onAddTracker = onAddTracker,
        onIneligibleTrackerTap = onIneligibleTrackerTap,
        onRefresh = onRefreshTrackers,
        onDone = onDone,
        onDismiss = onDismiss,
        onLeaveComposition = onLeaveComposition,
        dismissOnHostInactive = dismissOnHostInactive,
    )
}

@Composable
private fun PickerTabContent(
    groupId: String,
    modifier: Modifier = Modifier,
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    tabs: List<GeoVaultTab<PickerPhase>>,
    onRemoveTracker: (String) -> Unit,
    isLoading: Boolean,
    addingTrackerIds: Set<String>,
    onAddTracker: (String) -> Unit,
    onIneligibleTrackerTap: (Tracker) -> Unit,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onLeaveComposition: (() -> Unit)?,
    dismissOnHostInactive: Boolean,
    doneButtonLabel: String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { tabs.size },
    )
    var settledPhase by remember { mutableStateOf(PickerPhase.LIST) }
    val activePhase = tabs.getOrNull(pagerState.currentPage)?.value ?: settledPhase

    LaunchedEffect(pagerState.settledPage, tabs) {
        settledPhase = tabs.getOrNull(pagerState.settledPage)?.value ?: PickerPhase.LIST
    }

    val scrollToPhase: (PickerPhase, Boolean) -> Unit = { phase, animated ->
        val targetIndex = tabs.indexOfFirst { it.value == phase }
        if (targetIndex >= 0) {
            coroutineScope.launch {
                if (animated) {
                    pagerState.animateScrollToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            }
        }
    }

    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.NESTED_FULL_SCREEN_OVERLAY,
        onBack = {
            if (settledPhase == PickerPhase.ADD || activePhase == PickerPhase.ADD) {
                scrollToPhase(PickerPhase.LIST, true)
            } else {
                onDismiss()
            }
            true
        },
    )

    val dividerColor = geoVaultHairlineDividerColor()
    val cardBorderColor = geoVaultCardBorderColor()

    var addTabRecentlyAddedIds by remember(groupId) { mutableStateOf(emptySet<String>()) }
    var prevAddingTrackerIds by remember(groupId) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(addingTrackerIds, selectedTrackerIds) {
        val completed = prevAddingTrackerIds - addingTrackerIds
        val newlySuccessful = completed.filter { it in selectedTrackerIds }.toSet()
        if (newlySuccessful.isNotEmpty()) {
            addTabRecentlyAddedIds = addTabRecentlyAddedIds + newlySuccessful
        }
        prevAddingTrackerIds = addingTrackerIds
    }

    LaunchedEffect(selectedTrackerIds) {
        addTabRecentlyAddedIds = addTabRecentlyAddedIds intersect selectedTrackerIds
    }

    val memberItems = remember(allTrackers, selectedTrackerIds) {
        val knownById = allTrackers.associateBy { it.id }
        selectedTrackerIds.map { id ->
            TrackerRowItem(trackerId = id, tracker = knownById[id])
        }.sortedWith(NaturalSort.naturalOrderBy { it.displayName.lowercase() })
    }
    val addTabDisplayItems = remember(allTrackers, selectedTrackerIds, addTabRecentlyAddedIds) {
        allTrackers
            .filter { it.id !in selectedTrackerIds || it.id in addTabRecentlyAddedIds }
            .map { TrackerRowItem(trackerId = it.id, tracker = it) }
            .sortedWith(NaturalSort.naturalOrderBy { it.displayName.lowercase() })
    }
    val filteredItems = remember(addTabDisplayItems, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) addTabDisplayItems
        else addTabDisplayItems.filter {
            it.displayName.lowercase().contains(q) ||
                it.ownerEmail.lowercase().contains(q)
        }
    }
    val loadingTrackersText = stringResource(R.string.loading_trackers)

    GeoVaultSubViewScaffold(
        title = stringResource(R.string.groups_tracker_title),
        onClose = onDismiss,
        onLeaveComposition = onLeaveComposition,
        dismissOnHostInactive = dismissOnHostInactive,
        modifier = modifier,
        headerExtras = {
            GeoVaultTabBar(
                tabs = tabs,
                selectedTab = activePhase,
                onTabSelected = { scrollToPhase(it, true) },
                indicatorPage = pagerState.currentPage,
                indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
            )
            Divider(
                color = dividerColor,
                thickness = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = dividerColor,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = doneButtonLabel,
                    onClick = {
                        if (activePhase == PickerPhase.ADD) scrollToPhase(PickerPhase.LIST, true)
                        else onDone()
                    },
                    tooltip = if (activePhase == PickerPhase.ADD) {
                        stringResource(R.string.tooltip_group_trackers_list_add)
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            userScrollEnabled = true,
        ) { page ->
            when (tabs.getOrNull(page)?.value ?: PickerPhase.LIST) {
                PickerPhase.LIST -> {
                    if (memberItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.groups_tracker_list_empty),
                                style = MaterialTheme.typography.body2,
                                color = geoVaultContentSecondaryColor(),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 4.dp,
                                bottom = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(memberItems, key = { it.trackerId }) { item ->
                                MemberTrackerCard(
                                    item = item,
                                    context = context,
                                    borderColor = cardBorderColor,
                                    onRemove = { onRemoveTracker(item.trackerId) },
                                )
                            }
                        }
                    }
                }

                PickerPhase.ADD -> {
                    GeoVaultPullRefreshLoadingContainer(
                        refreshing = isLoading,
                        showBlockingLoader = false,
                        onRefresh = onRefresh,
                        pullRefreshEnabled = !isLoading,
                        showPullRefreshIndicator = !isLoading,
                        canRefresh = !isLoading,
                        loadingText = loadingTrackersText,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            GeoVaultSearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = stringResource(R.string.groups_tracker_picker_filter_hint),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )

                            Divider(
                                color = dividerColor,
                                thickness = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (filteredItems.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.groups_tracker_picker_empty),
                                        style = MaterialTheme.typography.body2,
                                        color = geoVaultContentSecondaryColor(),
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = 4.dp,
                                        bottom = 12.dp,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(filteredItems, key = { it.trackerId }) { item ->
                                        val isAdding = item.trackerId in addingTrackerIds
                                        val showAsAddedWithTrash =
                                            item.trackerId in addTabRecentlyAddedIds &&
                                                item.trackerId in selectedTrackerIds
                                        val isAddable = item.tracker?.let(
                                            GroupReshareAddabilityPolicy::isAddableToGroup
                                        ) ?: true
                                        AddableTrackerCard(
                                            item = item,
                                            isAdding = isAdding,
                                            showAsAddedWithTrash = showAsAddedWithTrash,
                                            isAddable = isAddable,
                                            borderColor = cardBorderColor,
                                            onAdd = { onAddTracker(item.trackerId) },
                                            onIneligibleTap = {
                                                item.tracker?.let(onIneligibleTrackerTap)
                                            },
                                            onUndoRecentAdd = {
                                                addTabRecentlyAddedIds =
                                                    addTabRecentlyAddedIds - item.trackerId
                                                onRemoveTracker(item.trackerId)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colors.background.copy(alpha = 0.94f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                GeoVaultLoadingSpinner(bottomText = loadingTrackersText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberTrackerCard(
    item: TrackerRowItem,
    context: Context,
    borderColor: Color,
    onRemove: () -> Unit,
) {
    val chevronTint = remember(item.tracker?.color) {
        TrackerChevronStylePolicy.tintForTrackerColorHex(item.tracker?.color)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackerChevronIcon(
                tint = chevronTint,
                modifier = Modifier.size(TrackerChevronStylePolicy.TrackerRowChevronSize),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.isOwned && item.ownerEmail.isNotBlank()) {
                    Text(
                        text = item.ownerEmail,
                        style = MaterialTheme.typography.caption,
                        color = geoVaultContentSecondaryColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GeoVaultIconButton(
                onClick = onRemove,
                tooltip = stringResource(R.string.tooltip_group_tracker_remove),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.trackers_dialog_cancel),
                    tint = GeoVaultColorTokens.Error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddableTrackerCard(
    item: TrackerRowItem,
    isAdding: Boolean,
    showAsAddedWithTrash: Boolean,
    isAddable: Boolean,
    borderColor: Color,
    onAdd: () -> Unit,
    onIneligibleTap: () -> Unit,
    onUndoRecentAdd: () -> Unit,
) {
    val flags = GeoVaultAddRemoveRowFlags(
        isPendingAdd = isAdding,
        isAdded = showAsAddedWithTrash,
        isDisabled = !isAddable && !showAsAddedWithTrash,
    )
    val noOpClick: () -> Unit = { }
    TrackerAddRowCard(
        name = item.displayName,
        ownerEmail = item.ownerEmail.takeIf { !item.isOwned },
        flags = flags,
        borderColor = borderColor,
        onAdd = when {
            flags.isDisabled -> onIneligibleTap
            showAsAddedWithTrash -> noOpClick
            else -> onAdd
        },
        onRemove = if (showAsAddedWithTrash) onUndoRecentAdd else noOpClick,
        addIconTooltip = if (flags.isDisabled) {
            null
        } else {
            stringResource(R.string.tooltip_add_group_row_add)
        },
        removeIconTooltip = if (showAsAddedWithTrash) {
            stringResource(R.string.tooltip_group_tracker_remove)
        } else {
            null
        },
    )
}

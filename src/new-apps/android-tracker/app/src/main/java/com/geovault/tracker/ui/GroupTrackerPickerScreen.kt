package com.geovault.tracker.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.NaturalSort
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.ui.components.GeoVaultInput
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultRequestBottomTabsDisabled
import com.geovault.common.ui.components.GeoVaultTab
import com.geovault.common.ui.components.GeoVaultTopTabBehavior
import com.geovault.common.ui.components.GeoVaultTopTabSurface
import com.geovault.common.ui.components.GeoVaultTopTabSwipeMode
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.parseHexToColorInt

private enum class PickerPhase { LIST, ADD }

private data class TrackerRowItem(
    val trackerId: String,
    val tracker: Tracker?,
) {
    val displayName: String get() = tracker?.name ?: trackerId
    val ownerEmail: String get() = tracker?.owner_email?.takeIf { it.isNotBlank() } ?: ""
    val isOwned: Boolean get() = tracker?.isOwner() == true
}

@Composable
fun GroupTrackerPickerScreen(
    groupName: String,
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    isLoading: Boolean = false,
    addingTrackerIds: Set<String> = emptySet(),
    onRefreshTrackers: () -> Unit = {},
    onSelectionChanged: (Set<String>) -> Unit,
    onAddTracker: (String) -> Unit = {},
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    doneButtonLabel: String = stringResource(R.string.trackers_edit_pick_users_done),
) {
    GeoVaultRequestBottomTabsDisabled(shouldDisable = true)

    var phase by remember { mutableStateOf(PickerPhase.LIST) }
    val tabs = remember {
        listOf(
            GeoVaultTab(
                value = PickerPhase.LIST,
                label = "",
            ),
            GeoVaultTab(
                value = PickerPhase.ADD,
                label = "",
            ),
        )
    }
    val localizedTabs = listOf(
        tabs[0].copy(label = stringResource(R.string.trackers_subtab_trackers)),
        tabs[1].copy(label = stringResource(R.string.groups_tracker_add_title)),
    )

    BackHandler {
        if (phase == PickerPhase.ADD) phase = PickerPhase.LIST else onDismiss()
    }
    PickerTabContent(
        allTrackers = allTrackers,
        selectedTrackerIds = selectedTrackerIds,
        tabs = localizedTabs,
        selectedPhase = phase,
        isLoading = isLoading,
        addingTrackerIds = addingTrackerIds,
        doneButtonLabel = doneButtonLabel,
        onPhaseSelected = { phase = it },
        onRemoveTracker = { id -> onSelectionChanged(selectedTrackerIds - id) },
        onAddTracker = onAddTracker,
        onRefresh = onRefreshTrackers,
        onDone = onDone,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PickerTabContent(
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    tabs: List<GeoVaultTab<PickerPhase>>,
    selectedPhase: PickerPhase,
    onPhaseSelected: (PickerPhase) -> Unit,
    onRemoveTracker: (String) -> Unit,
    isLoading: Boolean,
    addingTrackerIds: Set<String>,
    onAddTracker: (String) -> Unit,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    doneButtonLabel: String,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val borderColor = if (isSystemInDarkTheme()) {
        GeoVaultColorTokens.DarkBorderLight
    } else {
        GeoVaultColorTokens.BorderLight
    }

    val memberItems = remember(allTrackers, selectedTrackerIds) {
        val knownById = allTrackers.associateBy { it.id }
        selectedTrackerIds.map { id ->
            TrackerRowItem(trackerId = id, tracker = knownById[id])
        }.sortedWith(NaturalSort.naturalOrderBy { it.displayName.lowercase() })
    }
    val addableItems = remember(allTrackers, selectedTrackerIds) {
        allTrackers
            .filter { it.id !in selectedTrackerIds }
            .map { TrackerRowItem(trackerId = it.id, tracker = it) }
            .sortedWith(NaturalSort.naturalOrderBy { it.displayName.lowercase() })
    }
    val filteredItems = remember(addableItems, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) addableItems
        else addableItems.filter {
            it.displayName.lowercase().contains(q) ||
                it.ownerEmail.lowercase().contains(q)
        }
    }
    val loadingTrackersText = stringResource(R.string.loading_trackers)

    GeoVaultTopTabSurface(
        tabs = tabs,
        selectedTab = selectedPhase,
        onTabSelected = onPhaseSelected,
        behavior = GeoVaultTopTabBehavior(
            swipeMode = GeoVaultTopTabSwipeMode.ALWAYS,
            isTabRefreshing = { false },
            isTabBlocking = { tab -> tab == PickerPhase.ADD && isLoading },
            canRefreshTab = { tab -> tab == PickerPhase.ADD && !isLoading },
            isPullRefreshEnabled = { tab -> tab == PickerPhase.ADD && !isLoading },
            loadingTextForTab = { tab -> if (tab == PickerPhase.ADD) loadingTrackersText else null },
            onRefreshTab = { tab ->
                if (tab == PickerPhase.ADD && !isLoading) onRefresh()
            },
        ),
        contentForTab = { phase ->
            when (phase) {
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
                                color = GeoVaultColorTokens.TextSecondary,
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
                                    borderColor = borderColor,
                                    onRemove = { onRemoveTracker(item.trackerId) },
                                )
                            }
                        }
                    }
                }
                PickerPhase.ADD -> {
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
                                color = GeoVaultColorTokens.TextSecondary,
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
                            items(filteredItems, key = { it.trackerId }) { item ->
                                val isAdding = item.trackerId in addingTrackerIds
                                AddableTrackerCard(
                                    item = item,
                                    isAdding = isAdding,
                                    borderColor = borderColor,
                                    onAdd = { onAddTracker(item.trackerId) },
                                )
                            }
                        }
                    }
                }
            }
        },
        titleForTab = { tab ->
            GeoVaultCompactDismissTitleBar(
                title = if (tab == PickerPhase.LIST) {
                    stringResource(R.string.groups_tracker_list_title)
                } else {
                    stringResource(R.string.groups_tracker_add_title)
                },
                onClose = {
                    if (tab == PickerPhase.ADD) onPhaseSelected(PickerPhase.LIST)
                    else onDismiss()
                },
            )
        },
        headerForTab = { tab ->
            if (tab == PickerPhase.LIST) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = borderColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    GeoVaultPrimaryButton(
                        text = stringResource(R.string.groups_tracker_add_title),
                        onClick = { onPhaseSelected(PickerPhase.ADD) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                GeoVaultInput(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = stringResource(R.string.trackers_edit_share_user_picker_filter_label),
                    placeholder = stringResource(R.string.groups_tracker_picker_filter_hint),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                Divider(
                    color = borderColor,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomForTab = { tab ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                GeoVaultPrimaryButton(
                    text = doneButtonLabel,
                    onClick = {
                        if (tab == PickerPhase.ADD) onPhaseSelected(PickerPhase.LIST)
                        else onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun MemberTrackerCard(
    item: TrackerRowItem,
    context: Context,
    borderColor: Color,
    onRemove: () -> Unit,
) {
    val trackerColorInt = parseHexToColorInt(item.tracker?.color, context)

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
            Icon(
                painter = painterResource(R.drawable.ic_chevron_track),
                contentDescription = null,
                tint = Color(trackerColorInt),
                modifier = Modifier.size(18.dp),
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
                        color = GeoVaultColorTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
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
    borderColor: Color,
    onAdd: () -> Unit,
) {
    TrackerAddRowCard(
        name = item.displayName,
        ownerEmail = item.ownerEmail.takeIf { !item.isOwned },
        state = if (isAdding) TrackerAddRowActionState.ADDING else TrackerAddRowActionState.IDLE,
        borderColor = borderColor,
        onAdd = onAdd,
        onRemove = {},
    )
}

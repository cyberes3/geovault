package com.geovault.tracker.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
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

    BackHandler {
        if (phase == PickerPhase.ADD) phase = PickerPhase.LIST else onDismiss()
    }

    when (phase) {
        PickerPhase.LIST -> MemberListContent(
            allTrackers = allTrackers,
            selectedTrackerIds = selectedTrackerIds,
            onRemoveTracker = { id ->
                onSelectionChanged(selectedTrackerIds - id)
            },
            onAddTrackers = { phase = PickerPhase.ADD },
            onDone = onDone,
            onDismiss = onDismiss,
            doneButtonLabel = doneButtonLabel,
        )

        PickerPhase.ADD -> AddTrackersContent(
            allTrackers = allTrackers,
            selectedTrackerIds = selectedTrackerIds,
            isLoading = isLoading,
            addingTrackerIds = addingTrackerIds,
            onAddTracker = onAddTracker,
            onRefresh = onRefreshTrackers,
            onBack = { phase = PickerPhase.LIST },
        )
    }
}

@Composable
private fun MemberListContent(
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    onRemoveTracker: (String) -> Unit,
    onAddTrackers: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    doneButtonLabel: String,
) {
    val context = LocalContext.current
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

    Scaffold(
        topBar = {
            GeoVaultCompactDismissTitleBar(
                title = stringResource(R.string.groups_tracker_list_title),
                onClose = onDismiss,
            )
        },
        bottomBar = {
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
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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
                    onClick = onAddTrackers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
    }
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

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun AddTrackersContent(
    allTrackers: List<Tracker>,
    selectedTrackerIds: Set<String>,
    isLoading: Boolean,
    addingTrackerIds: Set<String>,
    onAddTracker: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var searchQuery by remember { mutableStateOf("") }
    val borderColor = if (isSystemInDarkTheme()) {
        GeoVaultColorTokens.DarkBorderLight
    } else {
        GeoVaultColorTokens.BorderLight
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

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = onRefresh,
    )

    Scaffold(
        topBar = {
            GeoVaultCompactDismissTitleBar(
                title = stringResource(R.string.groups_tracker_add_title),
                onClose = onBack,
            )
        },
        bottomBar = {
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
                    text = stringResource(R.string.trackers_edit_pick_users_done),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pullRefresh(pullRefreshState),
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GeoVaultLoadingSpinner(
                                bottomText = stringResource(R.string.loading_trackers),
                            )
                        }
                    }
                    filteredItems.isEmpty() -> {
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
                    }
                    else -> {
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
                if (!isLoading) {
                    PullRefreshIndicator(
                        refreshing = false,
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isAdding) Modifier else Modifier.clickable { onAdd() }),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_track),
                contentDescription = null,
                tint = GeoVaultColorTokens.PrimaryBlue,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
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
            if (isAdding) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GeoVaultLoadingSpinner(spinnerSize = 20.dp, strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

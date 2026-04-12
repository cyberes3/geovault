package com.geovault.tracker.ui.components

import android.graphics.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.components.GeoVaultInstallLongPressTooltip
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.trackGeoVaultTooltipBounds
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.ui.TrackerChevronIcon
import com.geovault.tracker.ui.TrackerChevronStylePolicy

data class TrackerItemCardModel(
    val title: String,
    val chevronColorHex: String?,
    val lastUpdateText: String,
    val coordinatesText: String?,
    val ownerEmail: String?,
    val isHighlighted: Boolean,
    val isSelected: Boolean,
    val canOpenMap: Boolean,
    val canEdit: Boolean,
)

@Composable
fun TrackerItemCard(
    model: TrackerItemCardModel,
    onOpenMap: () -> Unit,
    onViewParams: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chevronTint = remember(model.chevronColorHex) {
        TrackerChevronStylePolicy.tintForTrackerColorHex(model.chevronColorHex, context)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = 0.dp,
        backgroundColor = if (model.isHighlighted) GeoVaultColorTokens.Purple100 else GeoVaultColorTokens.Surface,
        border = BorderStroke(2.dp, GeoVaultColorTokens.PrimaryBlue),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackerChevronIcon(
                    tint = chevronTint,
                    modifier = Modifier.size(TrackerChevronStylePolicy.TrackerRowChevronSize),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = model.title,
                    modifier = Modifier.weight(1f),
                    color = GeoVaultColorTokens.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.lastUpdateText,
                    color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
                model.coordinatesText?.let { coordinates ->
                    Text(
                        text = " \u2022 ",
                        color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = coordinates,
                        color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            model.ownerEmail?.let { owner ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = owner,
                    color = GeoVaultColorTokens.TextSecondary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))
            } ?: Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GeoVaultPrimaryButton(
                    text = stringResource(R.string.nav_map),
                    onClick = onOpenMap,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled && model.canOpenMap,
                    tooltip = stringResource(R.string.tooltip_card_view_on_map),
                )
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.trackers_action_params),
                    onClick = onViewParams,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled,
                    tooltip = stringResource(R.string.tooltip_card_view_params),
                )
                GeoVaultSecondaryButton(
                    text = stringResource(R.string.trackers_action_edit),
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = enabled && model.canEdit,
                    tooltip = stringResource(R.string.tooltip_card_edit),
                )
            }
        }
    }
}

data class GroupItemCardModel(
    val title: String,
    val ownerEmail: String?,
    val trackerCount: Int,
    val isPending: Boolean,
    val isHighlighted: Boolean,
    val canOpenMap: Boolean,
    val canEdit: Boolean,
    val canOpenActions: Boolean,
)

@Composable
fun GroupItemCard(
    model: GroupItemCardModel,
    onOpenActions: () -> Unit,
    onOpenMap: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val groupTitleInteractionSource = remember { MutableInteractionSource() }
    var groupTitleBounds by remember { mutableStateOf<Rect?>(null) }
    val groupCardMenuTooltip = stringResource(R.string.tooltip_group_card_menu)
    GeoVaultInstallLongPressTooltip(
        tooltipText = groupCardMenuTooltip,
        enabled = enabled && model.canOpenActions,
        interactionSource = groupTitleInteractionSource,
        anchorBounds = groupTitleBounds,
    )
    val trackCountText = stringResource(R.string.trackers_meta_tracks_count, model.trackerCount)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = if (model.isHighlighted) GeoVaultColorTokens.Purple100 else GeoVaultColorTokens.Surface,
        border = BorderStroke(
            2.dp,
            if (model.isPending) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.PrimaryBlue
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_groups),
                contentDescription = null,
                tint = GeoVaultColorTokens.PrimaryBlue,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .trackGeoVaultTooltipBounds { groupTitleBounds = it }
                    .clickable(
                        interactionSource = groupTitleInteractionSource,
                        indication = null,
                        enabled = enabled && model.canOpenActions,
                        onClick = onOpenActions,
                    ),
            ) {
                Text(
                    text = model.title,
                    color = GeoVaultColorTokens.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                model.ownerEmail?.let {
                    Text(
                        text = it,
                        color = GeoVaultColorTokens.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (model.isPending) {
                        "${stringResource(R.string.trackers_badge_invite_pending)} \u00B7 $trackCountText"
                    } else {
                        trackCountText
                    },
                    color = if (model.isPending) GeoVaultColorTokens.MainYellow else GeoVaultColorTokens.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.canOpenMap || model.canEdit) {
                Box {
                    GeoVaultIconButton(
                        onClick = { menuExpanded = true },
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                        tooltip = groupCardMenuTooltip,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.PrimaryBlue,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (model.canOpenMap && !model.isPending) {
                            DropdownMenuItem(onClick = {
                                menuExpanded = false
                                onOpenMap()
                            }) {
                                Text(stringResource(R.string.trackers_action_view_group_on_map))
                            }
                        }
                        if (model.canEdit) {
                            DropdownMenuItem(onClick = {
                                menuExpanded = false
                                onEdit()
                            }) {
                                Text(stringResource(R.string.trackers_action_edit))
                            }
                        }
                    }
                }
            }
        }
    }
}

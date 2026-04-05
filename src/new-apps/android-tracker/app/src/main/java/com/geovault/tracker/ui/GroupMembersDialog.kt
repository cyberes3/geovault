package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker

data class GroupMembersOverlayState(
    val group: Group,
    val highlightedTrackerId: String?,
)

@Composable
fun GroupMembersDialog(
    group: Group,
    allTrackers: List<Tracker>,
    highlightedTrackerId: String?,
    onDismiss: () -> Unit,
    onViewTrackerOnMap: (Tracker) -> Unit,
    onViewTrackerParams: (Tracker) -> Unit,
    onViewTrackerInList: ((String) -> Unit)? = null,
) {
    GeoVaultInfoDialog(
        title = group.name,
        onDismissRequest = onDismiss,
        closeButtonText = stringResource(R.string.trackers_dialog_cancel),
    ) {
        val byId = allTrackers.associateBy { it.id }
        val members = group.track_ids.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (members.isEmpty()) {
            Text(
                text = stringResource(R.string.trackers_group_actions_empty),
                style = MaterialTheme.typography.body2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { trackerId ->
                    val tracker = byId[trackerId]
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        color = if (trackerId == highlightedTrackerId) {
                            MaterialTheme.colors.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colors.surface
                        },
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = tracker?.name ?: trackerId,
                                style = MaterialTheme.typography.subtitle2,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tracker != null) {
                                    GeoVaultInlineActionButton(
                                        text = stringResource(R.string.trackers_action_view_on_map),
                                        onClick = { onViewTrackerOnMap(tracker) },
                                        enabled = true,
                                    )
                                    GeoVaultInlineActionButton(
                                        text = stringResource(R.string.map_action_view_params),
                                        onClick = { onViewTrackerParams(tracker) },
                                        enabled = true,
                                    )
                                }
                                if (onViewTrackerInList != null) {
                                    GeoVaultInlineActionButton(
                                        text = stringResource(R.string.map_action_view_in_list),
                                        onClick = { onViewTrackerInList(trackerId) },
                                        enabled = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

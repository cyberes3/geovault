package com.geovault.tracker.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.tracker.R
import com.geovault.tracker.Tracker

data class TrackerParamsUiModel(
    val trackerName: String,
    val latitude: Double?,
    val longitude: Double?,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
    val isOwned: Boolean,
)

fun Tracker.toTrackerParamsUiModelOrNull(): TrackerParamsUiModel? {
    val point = last_point
    val latitude = point?.getOrNull(1)
    val longitude = point?.getOrNull(0)
    val pointEpoch = point?.getOrNull(2)?.toLong()?.let { raw ->
        if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }
    return TrackerParamsUiModel(
        trackerName = name.ifBlank { id },
        latitude = latitude,
        longitude = longitude,
        lastUpdatedMs = pointEpoch ?: updated_at,
        accuracyMeters = null,
        isOwned = isOwner(),
    )
}

@Composable
fun TrackerParamsDialog(
    model: TrackerParamsUiModel,
    onDismiss: () -> Unit,
) {
    GeoVaultInfoDialog(
        title = model.trackerName,
        onDismissRequest = onDismiss,
        closeButtonText = stringResource(R.string.trackers_dialog_cancel),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.latitude != null && model.longitude != null) {
                Text(
                    text = stringResource(
                        R.string.map_selection_coordinates,
                        model.latitude,
                        model.longitude
                    ),
                    style = MaterialTheme.typography.body2
                )
            } else {
                Text(
                    text = stringResource(R.string.waiting_for_data),
                    style = MaterialTheme.typography.body2
                )
            }
            model.accuracyMeters?.let { accuracy ->
                Text(
                    text = stringResource(R.string.map_selection_accuracy_meters, accuracy),
                    style = MaterialTheme.typography.body2
                )
            }
            model.lastUpdatedMs?.let { updatedMs ->
                val ago = DateUtils.getRelativeTimeSpanString(
                    updatedMs,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS
                )
                Text(
                    text = stringResource(R.string.map_selection_updated_ago, ago),
                    style = MaterialTheme.typography.body2
                )
            }
            Text(
                text = if (model.isOwned) {
                    stringResource(R.string.trackers_badge_owner)
                } else {
                    stringResource(R.string.trackers_badge_not_owner)
                },
                style = MaterialTheme.typography.body2
            )
        }
    }
}

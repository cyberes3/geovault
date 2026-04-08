package com.geovault.tracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R

enum class TrackerAddRowActionState {
    IDLE,
    ADDING,
    REMOVING,
    ADDED_DELETE,
}

@Composable
fun TrackerAddRowCard(
    name: String,
    ownerEmail: String?,
    iconRes: Int = R.drawable.ic_chevron_track,
    iconTint: Color = TrackerChevronStylePolicy.DefaultAddRowTint,
    state: TrackerAddRowActionState,
    borderColor: Color,
    enabled: Boolean = true,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (state == TrackerAddRowActionState.ADDING || state == TrackerAddRowActionState.REMOVING) Modifier
                else Modifier.clickable(enabled = enabled, onClick = onAdd)
            ),
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
            if (iconRes == R.drawable.ic_chevron_track) {
                TrackerChevronIcon(
                    tint = iconTint,
                    modifier = Modifier.size(TrackerChevronStylePolicy.TrackerRowChevronSize),
                )
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ownerEmail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.caption,
                        color = GeoVaultColorTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (state) {
                TrackerAddRowActionState.ADDING,
                TrackerAddRowActionState.REMOVING,
                -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GeoVaultLoadingSpinner(spinnerSize = 20.dp, strokeWidth = 2.dp)
                    }
                }
                TrackerAddRowActionState.ADDED_DELETE -> {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.Error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                TrackerAddRowActionState.IDLE -> {
                    IconButton(onClick = onAdd, enabled = enabled) {
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
}

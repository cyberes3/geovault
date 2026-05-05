package com.geovault.tracker.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultAddRemoveRowCard
import com.geovault.common.ui.components.GeoVaultAddRemoveRowFlags
import com.geovault.common.ui.components.GeoVaultAddRemoveRowStatePolicy
import com.geovault.tracker.R

@Composable
fun TrackerAddRowCard(
    name: String,
    ownerEmail: String?,
    iconRes: Int = R.drawable.ic_chevron_track,
    iconTint: Color = TrackerChevronStylePolicy.DefaultAddRowTint,
    flags: GeoVaultAddRemoveRowFlags,
    borderColor: Color,
    enabled: Boolean = true,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    addIconTooltip: String? = null,
    removeIconTooltip: String? = null,
) {
    val state = GeoVaultAddRemoveRowStatePolicy.resolve(flags)
    GeoVaultAddRemoveRowCard(
        name = name,
        subtitle = ownerEmail,
        state = state,
        borderColor = borderColor,
        enabled = enabled,
        onAdd = onAdd,
        onRemove = onRemove,
        modifier = modifier,
        addIconTooltip = addIconTooltip,
        removeIconTooltip = removeIconTooltip,
        leadingContent = {
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
        },
    )
}

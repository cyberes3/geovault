package com.geovault.common.ui.components

import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geovault.common.R
import com.geovault.common.sort.GeoVaultFileListSortMode
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Composable
fun GeoVaultFileListSortButton(
    sortMode: GeoVaultFileListSortMode,
    onSortModeSelected: (GeoVaultFileListSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val onSortModeSelectedState = rememberUpdatedState(onSortModeSelected)
    val options = GeoVaultFileListSortMode.entries.map { mode ->
        GeoVaultSelectOption(value = mode, label = mode.label)
    }
    val sortTooltip = stringResource(R.string.gv_common_tooltip_sort)

    GeoVaultIconButton(
        onClick = { showDialog = true },
        modifier = modifier,
        tooltip = sortTooltip,
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = sortTooltip,
            tint = GeoVaultColorTokens.MainBlue,
        )
    }

    if (showDialog) {
        GeoVaultSingleSelectDialog(
            title = "Sort by",
            options = options,
            selectedValue = sortMode,
            onSelected = { onSortModeSelectedState.value(it) },
            onDismissRequest = { showDialog = false },
        )
    }
}

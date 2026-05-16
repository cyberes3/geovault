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

    GeoVaultIconButton(
        onClick = { showDialog = true },
        modifier = modifier,
        tooltip = "Sort",
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = "Sort",
            tint = GeoVaultColorTokens.MainBlue,
        )
    }

    if (showDialog) {
        GeoVaultSingleSelectDialog(
            title = "Sort by",
            options = options,
            selectedValue = sortMode,
            onSelected = { onSortModeSelectedState.value(it) },
            onDismiss = { showDialog = false },
        )
    }
}

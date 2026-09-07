package com.geovault.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun GeoVaultClearTransientStateWhenRequested(
    shouldClear: Boolean,
    onClear: () -> Unit,
) {
    LaunchedEffect(shouldClear) {
        if (shouldClear) onClear()
    }
}

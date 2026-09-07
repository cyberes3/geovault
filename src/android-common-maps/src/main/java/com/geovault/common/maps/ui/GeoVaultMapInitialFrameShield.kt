package com.geovault.common.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.geovault.common.ui.components.GeoVaultLoadingSpinner

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GeoVaultMapInitialFrameShield(
    visible: Boolean,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .pointerInteropFilter { true },
        contentAlignment = Alignment.Center,
    ) {
        GeoVaultLoadingSpinner(bottomText = statusText)
    }
}

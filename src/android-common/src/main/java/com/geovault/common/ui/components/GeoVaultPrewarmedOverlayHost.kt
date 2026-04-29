package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

object GeoVaultPrewarmedOverlayDefaults {
    const val PrewarmDelayMillis: Long = 300L
    const val VisibleZIndex: Float = 10f
    const val HiddenZIndex: Float = -1f
}

/**
 * Hosts a full-screen shell overlay and pre-composes it shortly after the shell settles.
 *
 * Settings, filters, and similar shell-level surfaces are often expensive enough that composing
 * them for the first time on tap can hitch. This host centralizes the "compose hidden, then reveal"
 * pattern so app shells only provide their overlay content and visible state.
 */
@Composable
fun GeoVaultPrewarmedOverlayHost(
    visible: Boolean,
    modifier: Modifier = Modifier,
    prewarmDelayMillis: Long = GeoVaultPrewarmedOverlayDefaults.PrewarmDelayMillis,
    visibleZIndex: Float = GeoVaultPrewarmedOverlayDefaults.VisibleZIndex,
    hiddenZIndex: Float = GeoVaultPrewarmedOverlayDefaults.HiddenZIndex,
    content: @Composable () -> Unit,
) {
    var prewarmed by remember { mutableStateOf(false) }

    LaunchedEffect(prewarmDelayMillis) {
        if (prewarmDelayMillis > 0L) {
            delay(prewarmDelayMillis)
        }
        prewarmed = true
    }

    if (visible || prewarmed) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .alpha(if (visible) 1f else 0f)
                .zIndex(if (visible) visibleZIndex else hiddenZIndex)
                .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
        ) {
            content()
        }
    }
}

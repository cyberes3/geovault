package com.geovault.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler

val LocalGeoVaultShellSettingsOverlayActive = staticCompositionLocalOf { false }

@Composable
fun GeoVaultShellSettingsOverlayActiveProvider(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGeoVaultShellSettingsOverlayActive provides active,
        content = content,
    )
}

/**
 * Standard host for app-level Settings surfaces.
 *
 * Settings is shell state, not a tab destination: it is rendered above the current view while the
 * current bottom-nav selection and tab stacks remain intact. Hidden prewarm composition does not
 * mark the settings overlay active, so shell chrome remains enabled until the overlay is visible.
 */
@Composable
fun GeoVaultShellSettingsOverlayHost(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    handleBack: Boolean = true,
    backPriority: Int = 0,
    prewarmDelayMillis: Long = GeoVaultPrewarmedOverlayDefaults.PrewarmDelayMillis,
    content: @Composable () -> Unit,
) {
    GeoVaultRegisterBackHandler(
        enabled = handleBack && visible,
        priority = backPriority,
        onBack = {
            onDismissRequest()
            true
        },
    )
    GeoVaultPrewarmedOverlayHost(
        visible = visible,
        modifier = modifier,
        prewarmDelayMillis = prewarmDelayMillis,
    ) {
        GeoVaultShellSettingsOverlayActiveProvider(active = visible, content = content)
    }
}

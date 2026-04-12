package com.geovault.common.ui.snackbar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * Shared layout for app-level snackbars: expand to the parent’s bounds so insets and
 * bottom alignment match across GeoVault apps (full-bleed overlay, not inside content padding).
 */
object GeoVaultSnackbarOverlayDefaults {
    val hostModifier: Modifier = Modifier.fillMaxSize()
}

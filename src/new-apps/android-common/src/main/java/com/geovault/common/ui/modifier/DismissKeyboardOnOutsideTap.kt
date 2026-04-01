package com.geovault.common.ui.modifier

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Clears focus and hides the soft keyboard when the user taps on this composable's
 * area and the event is not consumed by a focused child (for example a text field).
 *
 * Apply on a scroll column or screen content that wraps text fields; see [com.geovault.common.ui.components.GeoVaultInput].
 */
fun Modifier.dismissKeyboardOnOutsideTap(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed Modifier
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
        )
    }
}

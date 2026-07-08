package com.geovault.common.ui.modifier

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Clears Compose focus and hides the soft keyboard. Use for explicit dismissal from
 * non-Compose callbacks or other places that do not see [dismissKeyboardOnOutsideTap].
 */
fun dismissKeyboardClearingFocus(
    focusManager: FocusManager,
    keyboardController: SoftwareKeyboardController?,
) {
    focusManager.clearFocus(force = true)
    keyboardController?.hide()
}

/**
 * Clears focus and hides the soft keyboard after a tap gesture that reaches this composable,
 * using [PointerEventPass.Initial] so ancestors run before descendants consume the event (for
 * example [androidx.compose.material.Button]). Drag gestures are ignored so focused inputs can
 * stay active while their surrounding form scrolls above the keyboard.
 *
 * Only fires while the software keyboard is actually visible. This modifier is mounted on
 * every screen's full-screen root (see [com.geovault.common.ui.theme.GeoVaultTheme]), including
 * screens with no text input at all. Without the visibility check, ANY stationary tap-and-release
 * anywhere on the screen — including the tap-and-hold used to long-press-select text inside a
 * [androidx.compose.foundation.text.selection.SelectionContainer] — would force-clear focus,
 * which tears down the selection's toolbar/focus state and clears the selection almost instantly.
 * Ordinary `TextField`s hide this same bug because they re-request focus as part of handling
 * their own tap, invisibly undoing the clear in the same gesture; read-only selectable text has
 * no such self-healing.
 *
 * Apply on a full-screen root (see [com.geovault.common.ui.theme.GeoVaultTheme]) or on
 * scrollable form containers; see [com.geovault.common.ui.components.GeoVaultInput].
 */
fun Modifier.dismissKeyboardOnOutsideTap(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed Modifier
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            var movedPastTouchSlop = false
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                event.changes.forEach { change ->
                    val movedPastSlop =
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (change.pressed && movedPastSlop) {
                        movedPastTouchSlop = true
                    }
                }
            } while (event.changes.any { it.pressed })
            val imeVisible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!movedPastTouchSlop && imeVisible) {
                dismissKeyboardClearingFocus(focusManager, keyboardController)
            }
        }
    }
}

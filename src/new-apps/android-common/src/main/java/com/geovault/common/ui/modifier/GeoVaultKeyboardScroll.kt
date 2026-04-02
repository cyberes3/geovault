package com.geovault.common.ui.modifier

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

/**
 * Vertical scroll that stays usable when the soft keyboard is open: chains [verticalScroll] with
 * [imePadding] so extra bottom space appears while the IME is visible and fields can scroll above it.
 *
 * Use on the same scrollable column (or box) that wraps text fields. Pair with a
 * [androidx.compose.foundation.rememberScrollState] held in the caller.
 *
 * For `LazyColumn` / `LazyRow`, use [geoVaultKeyboardAwareListInset] on the lazy container instead
 * (there is no single scroll state to pass).
 *
 * View/XML screens should continue to use [com.geovault.common.KeyboardScrollHelper] with
 * [androidx.core.widget.NestedScrollView].
 */
fun Modifier.geoVaultKeyboardAwareVerticalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
): Modifier {
    if (!enabled) return this
    return this
        .verticalScroll(state, enabled, flingBehavior, reverseScrolling)
        .imePadding()
}

/**
 * Adds IME bottom inset for lazy lists (or other scrollables without a shared [ScrollState]).
 * Apply after sizing modifiers on the same [androidx.compose.foundation.lazy.LazyColumn] that holds inputs.
 */
fun Modifier.geoVaultKeyboardAwareListInset(): Modifier = imePadding()

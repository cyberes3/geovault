package com.geovault.common.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single shared "now" ticker for Compose UIs. Returns a [State]<[Long]> holding
 * `System.currentTimeMillis()` that re-publishes on the supplied [updateInterval],
 * driving recomposition naturally for any callers that read the value.
 *
 * Use this everywhere a screen needs to render relative-time text ("X ago") or any
 * other clock-derived value, instead of hand-rolling `LaunchedEffect { while (true) { delay(...); tick++ } }`.
 *
 * Lifecycle: the underlying [LaunchedEffect] coroutine is scoped to the call site's
 * composition; leaving the composition cancels the ticker automatically.
 */
@Composable
fun rememberNowMs(updateInterval: Duration = 1.seconds): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(updateInterval) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(updateInterval)
        }
    }
    return state
}

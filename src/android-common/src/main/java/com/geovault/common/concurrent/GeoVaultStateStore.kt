package com.geovault.common.concurrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sole writer of a [StateFlow]. [update] is atomic: the transform sees a stable snapshot and
 * no other [update] interleaves before the new value is published.
 */
class GeoVaultStateStore<T>(initial: T) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state.asStateFlow()

    val value: T
        get() = _state.value

    fun update(transform: (T) -> T): T {
        synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            return next
        }
    }

    fun replace(value: T): T = update { value }
}

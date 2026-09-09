package com.geovault.common.maps.ui.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generation-stamped camera command bus. Feature code resolves what the camera should do;
 * this bus owns minting, dedup, and stale-generation discard.
 *
 * A directive's [generation] is frozen at mint time. [onUserGestureStarted] / [onUserOwnedZoom]
 * bump the live generation so a consumer can discard any command queued before the user took
 * over the camera.
 */
class GeoVaultMapCameraDirectiveBus<D>(
    initial: D,
) {
    private val directiveMutable = MutableStateFlow(initial)
    val directive: StateFlow<D> = directiveMutable.asStateFlow()

    private val generationMutable = MutableStateFlow(0L)
    val generationFlow: StateFlow<Long> = generationMutable.asStateFlow()
    val generation: Long get() = generationMutable.value

    var userOwnsZoom: Boolean = false
        private set

    private var lastKey: Any? = Unset
    private var nextId: Long = 1L

    fun onUserGestureStarted() {
        userOwnsZoom = false
        generationMutable.value += 1
    }

    fun onUserOwnedZoom() {
        userOwnsZoom = true
        generationMutable.value += 1
    }

    fun resetDedup() {
        lastKey = Unset
    }

    fun publishIfUnchanged(key: Any?, mint: (id: Long, generation: Long) -> D) {
        if (key == lastKey) return
        lastKey = key
        directiveMutable.value = mint(nextId++, generation)
    }

    fun publish(mint: (id: Long, generation: Long) -> D) {
        lastKey = Unset
        directiveMutable.value = mint(nextId++, generation)
    }

    private object Unset
}

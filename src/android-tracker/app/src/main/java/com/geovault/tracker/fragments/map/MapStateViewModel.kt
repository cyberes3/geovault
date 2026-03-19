package com.geovault.tracker.fragments.map

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class MapStateViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    internal var latestSavedState: MapSavedState?
        get() {
            val bundle = savedStateHandle.get<Bundle>(KEY_SAVED_STATE_BUNDLE) ?: return null
            return MapSavedState.readFrom(bundle)
        }
        set(value) {
            if (value == null) {
                savedStateHandle.remove<Bundle>(KEY_SAVED_STATE_BUNDLE)
                return
            }
            val bundle = Bundle()
            value.writeTo(bundle)
            savedStateHandle[KEY_SAVED_STATE_BUNDLE] = bundle
        }

    private companion object {
        const val KEY_SAVED_STATE_BUNDLE = "map_saved_state_bundle"
    }
}


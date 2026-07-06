package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapRenderPackage
import com.geovault.tracker.presentation.TrackerMapUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the map's reactive core: the single [TrackerMapUiState] every subsystem reads and mutates,
 * and the [TrackerMapRenderPackage] derived from it for the UI layer. Every other piece of shared
 * map state has an owning subsystem; this is the one slice that is genuinely shared by all of
 * them, so it gets its own dedicated, independently-testable home instead of living as anonymous
 * fields on [TrackerMapRuntime] -- the same treatment [TrackerMapCameraCoordinator] already gets
 * for the camera-directive slice. Subsystems still mutate through `uiStateMutable`/
 * `renderPackageMutable` directly (this hub doesn't gatekeep individual writes), so this is a
 * grouping seam, not a new invariant-enforcing layer.
 */
internal class TrackerMapStateHub {
    val uiStateMutable = MutableStateFlow(TrackerMapUiState())
    val uiState: StateFlow<TrackerMapUiState> = uiStateMutable.asStateFlow()

    val renderPackageMutable = MutableStateFlow(TrackerMapRenderPackage())
    val renderPackage: StateFlow<TrackerMapRenderPackage> = renderPackageMutable.asStateFlow()
}

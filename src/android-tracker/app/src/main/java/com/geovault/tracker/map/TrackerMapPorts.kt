package com.geovault.tracker.map

import android.app.Application
import kotlinx.coroutines.CoroutineScope

/**
 * The map runtime's two Android/lifecycle-bound inputs it cannot construct for itself: the
 * [Application] context (for prefs, DB, and repository access) and the owning ViewModel's
 * [viewModelScope] (so every subsystem launches coroutines scoped to the same lifecycle without
 * each needing its own reference to the ViewModel). Passed in once at construction rather than
 * looked up statically so [TrackerMapRuntime] and its subsystems stay unit-testable with fakes.
 */
internal class TrackerMapPorts(
    val application: Application,
    val viewModelScope: CoroutineScope,
)

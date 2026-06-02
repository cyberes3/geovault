package com.geovault.tracker.map

import android.app.Application
import kotlinx.coroutines.CoroutineScope

internal class TrackerMapPorts(
    val application: Application,
    val viewModelScope: CoroutineScope,
)

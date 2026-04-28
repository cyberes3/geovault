package com.geovault.common.maps.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Hoisted fine-or-coarse location permission for map routes. Initial read uses
 * [geoVaultMapHasFineOrCoarseLocation]; [Lifecycle.Event.ON_RESUME] re-reads so grants from
 * system settings are picked up without restarting the activity.
 *
 * Assign [MutableState.value] after [androidx.activity.compose.rememberLauncherForActivityResult]
 * permission dialogs using [geoVaultMapHasFineOrCoarseLocation] again — those often do not emit
 * ON_RESUME on the same frame.
 */
@Composable
fun rememberGeoVaultMapLocationPermissionState(): MutableState<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember {
        mutableStateOf(context.geoVaultMapHasFineOrCoarseLocation())
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = context.geoVaultMapHasFineOrCoarseLocation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

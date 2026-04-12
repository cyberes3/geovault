package com.geovault.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

class GeoVaultConnectingButtonState internal constructor(
    val isEffectivelyConnecting: Boolean,
    private val onTrigger: () -> Unit,
) {
    fun onClick() = onTrigger()
}

@Composable
fun rememberConnectingButtonState(
    isConnecting: Boolean,
    onConnect: () -> Unit,
    timeoutMs: Long = 5000L,
): GeoVaultConnectingButtonState {
    var localConnecting by rememberSaveable { mutableStateOf(false) }
    var awaitingExternalStart by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            localConnecting = true
            awaitingExternalStart = false
        } else {
            awaitingExternalStart = false
            localConnecting = false
        }
    }
    LaunchedEffect(awaitingExternalStart) {
        if (awaitingExternalStart) {
            delay(timeoutMs)
            if (awaitingExternalStart && !isConnecting) {
                awaitingExternalStart = false
                localConnecting = false
            }
        }
    }

    return GeoVaultConnectingButtonState(
        isEffectivelyConnecting = isConnecting || localConnecting,
        onTrigger = {
            localConnecting = true
            awaitingExternalStart = true
            onConnect()
        },
    )
}

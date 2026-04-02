package com.geovault.common.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

val LocalGeoVaultBackRegistry = staticCompositionLocalOf<GeoVaultBackRegistry> {
    error("GeoVault back registry is not available. Wrap content with GeoVaultBackHandlerHost.")
}

@Composable
fun GeoVaultBackHandlerHost(content: @Composable () -> Unit) {
    val registry = remember { GeoVaultBackRegistry() }
    GeoVaultBackHandlerHost(
        registry = registry,
        content = content,
    )
}

@Composable
fun GeoVaultBackHandlerHost(
    registry: GeoVaultBackRegistry,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current.findComponentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val registrationCount by registry.registrationCountState
    val callbackRef = remember { arrayOfNulls<OnBackPressedCallback>(1) }

    DisposableEffect(activity, lifecycleOwner, registry) {
        if (activity == null) {
            onDispose {}
        } else {
            val callback = object : OnBackPressedCallback(registry.hasRegisteredNavigators()) {
                override fun handleOnBackPressed() {
                    if (registry.dispatchBack()) return
                    isEnabled = false
                    try {
                        activity.onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = registry.hasRegisteredNavigators()
                    }
                }
            }
            callbackRef[0] = callback
            activity.onBackPressedDispatcher.addCallback(lifecycleOwner, callback)
            onDispose {
                callbackRef[0]?.remove()
                callbackRef[0] = null
            }
        }
    }

    SideEffect {
        callbackRef[0]?.isEnabled = registrationCount > 0
    }

    CompositionLocalProvider(LocalGeoVaultBackRegistry provides registry) {
        content()
    }
}

@Composable
fun GeoVaultRegisterBackNavigator(
    navigator: GeoVaultBackNavigator,
    enabled: Boolean = true,
    priority: Int = 0,
) {
    val registry = LocalGeoVaultBackRegistry.current
    DisposableEffect(registry, navigator, enabled, priority) {
        if (!enabled) {
            onDispose {}
        } else {
            val registration = registry.register(
                navigator = navigator,
                priority = priority,
            )
            onDispose {
                registration.unregister()
            }
        }
    }
}

@Composable
fun GeoVaultRegisterBackHandler(
    enabled: Boolean = true,
    priority: Int = 0,
    canGoBack: () -> Boolean = { true },
    onBack: () -> Boolean,
) {
    val canGoBackState = rememberUpdatedState(canGoBack)
    val onBackState = rememberUpdatedState(onBack)
    val navigator = remember {
        object : GeoVaultBackNavigator {
            override fun canGoBack(): Boolean = canGoBackState.value()

            override fun goBack(): Boolean = onBackState.value()
        }
    }
    GeoVaultRegisterBackNavigator(
        navigator = navigator,
        enabled = enabled,
        priority = priority,
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

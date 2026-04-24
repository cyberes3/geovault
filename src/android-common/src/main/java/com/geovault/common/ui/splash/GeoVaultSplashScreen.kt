package com.geovault.common.ui.splash

import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared GeoVault splash-screen entry point.
 *
 * Wraps `androidx.core.splashscreen.installSplashScreen()` + `setKeepOnScreenCondition`
 * behind two tiny factory methods so every app in the GeoVault family installs the OS
 * splash the same way and holds it on-screen on the same contract (a `() -> Boolean`
 * predicate or a `StateFlow<Boolean>` readiness signal).
 *
 * Paired with the `Theme.GeoVault.Splash` parent style in `android-common/res/values/themes.xml`,
 * consumers get a branded splash with zero ceremony:
 *
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         GeoVaultSplashScreen.install(this, coldStart.isReady)
 *         super.onCreate(savedInstanceState)
 *         ...
 *     }
 * }
 * ```
 *
 * The [install] call MUST happen before `super.onCreate(…)` — that is a hard requirement of
 * the underlying androidx SplashScreen API, not something this helper can relax.
 */
object GeoVaultSplashScreen {

    /**
     * Install the OS splash and keep it visible while [keepOnScreen] returns `true`.
     *
     * Returns the underlying [SplashScreen] so callers can attach an exit animation via
     * `setOnExitAnimationListener { … }` if they need to. Most callers can ignore the
     * return value.
     */
    fun install(
        activity: ComponentActivity,
        keepOnScreen: () -> Boolean,
    ): SplashScreen {
        val splash = activity.installSplashScreen()
        splash.setKeepOnScreenCondition { keepOnScreen() }
        return splash
    }

    /**
     * Install the OS splash and keep it visible while [readyState]'s current value is
     * `false`. Convenience overload for the common case where the app exposes an
     * `isReady: StateFlow<Boolean>` from its cold-start orchestrator.
     */
    fun install(
        activity: ComponentActivity,
        readyState: StateFlow<Boolean>,
    ): SplashScreen = install(activity) { !readyState.value }
}

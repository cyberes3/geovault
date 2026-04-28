package com.geovault.common.ui.modifier

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reserve safe-area padding for the system navigation bar using the *visibility-ignoring*
 * inset.
 *
 * MapLibre's `MapView` re-measures its GL surface every time its container height changes.
 * On screen-off → resume the OS dispatches several transient `WindowInsets` values over a
 * handful of frames (zero while the keyguard animates away, then the real navigation-bar
 * inset). The vanilla `Modifier.navigationBarsPadding()` reads
 * [WindowInsets.Companion.navigationBars] which honors those visibility transitions and
 * therefore shrinks/grows the layout each frame — that's the user-visible "map squishes up
 * and down before settling" symptom.
 *
 * [WindowInsets.Companion.navigationBarsIgnoringVisibility] reports the inset *as if* the
 * navigation bar is showing, regardless of whether the system has hidden it for a transient
 * UI animation. It only changes when the navigation bar's actual layout changes (rotation,
 * gesture-nav vs. button-nav switch, foldable hinge), so layouts that pad with this inset
 * remain stable across keyguard / IME / status-bar visibility flips.
 *
 * This is the modifier the GeoVault common library uses everywhere it would previously have
 * called `Modifier.navigationBarsPadding()`. The standard "consume on apply" semantics of
 * [Modifier.windowInsetsPadding] still hold: descendants that re-pad with the same inset
 * type read zero, so nesting (e.g. [com.geovault.common.maps.ui.scaffold.GeoVaultMapScaffold]
 * inside `GeoVaultBottomNavScaffold`) does not double-pad.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.geoVaultStableNavigationBarsPadding(): Modifier =
    this.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

/**
 * Reserve safe-area padding for the system status bar using the *visibility-ignoring*
 * inset, for the same reason as [geoVaultStableNavigationBarsPadding] — `Modifier
 * .statusBarsPadding()` flips between zero and the real status-bar height during keyguard /
 * screen-off animations, which translates into a fluctuating top-bar height (and therefore
 * a fluctuating Material `Scaffold` content area) over several frames. Components that pad
 * their top edge with the system status-bar safe-area should prefer this variant so any
 * map subtree below them does not visibly jiggle on resume.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.geoVaultStableStatusBarsPadding(): Modifier =
    this.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)

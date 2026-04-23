package com.geovault.common.ui.components

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Provides a dedicated [ViewModelStoreOwner] scoped to a single UI entry.
 *
 * Any `viewModel()` call inside [content] resolves to a [ViewModelStore] keyed by
 * [entryId]. When the composable leaves the composition (overlay popped, tab torn
 * down, etc.) the store is cleared, invoking `onCleared()` on every VM it held.
 * Re-entering with the same [entryId] creates a fresh store, so sibling entries
 * with the same screen type but distinct identities never share VMs.
 *
 * The owner also implements [HasDefaultViewModelProviderFactory] with the standard
 * [ViewModelProvider.AndroidViewModelFactory]. This lets subtree callers use
 * `viewModel<SomeAndroidViewModel>()` or pass a custom `viewModelFactory { ... }`
 * without every call site having to thread `Application` through manually.
 *
 * Typical use is to wrap an overlay host so each overlay entry gets its own VM
 * lifetime:
 *
 * ```
 * GeoVaultOverlayViewModelStoreOwner(entryId = entry.id) {
 *     OverlayContent(entry.screen)
 * }
 * ```
 *
 * This primitive is intentionally independent of any nav library — callers define
 * what "entry" means (nav stack entry, tab slot, ad-hoc overlay key, …) and supply
 * a stable identity.
 */
@Composable
fun GeoVaultOverlayViewModelStoreOwner(
    entryId: String,
    content: @Composable () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val store = remember(entryId) { ViewModelStore() }
    DisposableEffect(store) {
        onDispose { store.clear() }
    }
    val owner = remember(store, application) {
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore get() = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
                ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            override val defaultViewModelCreationExtras: CreationExtras =
                MutableCreationExtras().apply {
                    set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
                }
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}

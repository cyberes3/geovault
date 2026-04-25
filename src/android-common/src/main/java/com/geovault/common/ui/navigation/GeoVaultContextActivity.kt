package com.geovault.common.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Walks [ContextWrapper] chains to find the hosting [ComponentActivity], if any.
 *
 * Used by [GeoVaultBackHandlerHost] and by app shells that need an activity-scoped
 * [androidx.lifecycle.ViewModelStoreOwner] (e.g. shared export controllers).
 */
tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }

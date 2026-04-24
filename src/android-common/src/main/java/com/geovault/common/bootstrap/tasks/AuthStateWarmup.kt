package com.geovault.common.bootstrap.tasks

import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.bootstrap.AuthStateCache
import com.geovault.common.bootstrap.GateTask

/**
 * Reads the initial logged-in flag from the auth subsystem and stashes it into
 * [AuthStateCache] so view-model state can be seeded synchronously by the first
 * composition.
 *
 * This is a [GateTask] because virtually every screen branches on auth state on its
 * very first frame — deferring it past `onCreate` would force a "logged out" flash
 * before a flicker into the real UI.
 *
 * Constructed with a `provider` rather than a controller instance because the auth
 * controller is itself initialized inside [com.geovault.common.bootstrap.GeoVaultAppBootstrap]
 * during `auth(...)` and we want to defer resolution until the gate actually executes.
 */
class AuthStateWarmup(
    private val provider: (Context) -> CommonInitialAuthController,
) : GateTask("auth-state") {

    override suspend fun execute(context: Context) {
        val controller = provider(context)
        AuthStateCache.setForBootstrap(controller.isLoggedIn())
    }
}

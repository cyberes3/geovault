package com.geovault.common.bootstrap

/**
 * Volatile cache of the current authenticated/anonymous flag, populated by the
 * [com.geovault.common.bootstrap.tasks.AuthStateWarmup] gate task and read synchronously
 * by view models seeding their initial state.
 *
 * Every GeoVault app needs this exact flag pre-composition; subsystem-specific session
 * bits (e.g. Survey's "guest mode") live in app-owned caches alongside this one.
 */
object AuthStateCache {
    @Volatile
    var isAuthenticated: Boolean = false
        private set

    /**
     * Internal API used by [com.geovault.common.bootstrap.tasks.AuthStateWarmup].
     * Apps must not call this directly.
     */
    fun setForBootstrap(value: Boolean) {
        isAuthenticated = value
    }
}

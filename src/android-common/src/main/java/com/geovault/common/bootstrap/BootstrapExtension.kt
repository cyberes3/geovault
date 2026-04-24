package com.geovault.common.bootstrap

import android.app.Application

/**
 * Pluggable subsystem hook for [GeoVaultAppBootstrap].
 *
 * An extension contributes initialization logic, gate tasks, background tasks, and/or
 * reset hooks to the app's bootstrap. This keeps `:android-common` independent of
 * subsystem-specific dependencies (maps, telemetry, …) while letting apps wire those
 * subsystems in with a single `.install(MyExtension(...))` call.
 *
 * Example: [com.geovault.common.maps.bootstrap.GeoVaultMapsBootstrap] installs MapLibre,
 * registers a main-map preload background task, and registers a reset hook that releases
 * the map controller on auth failure.
 */
interface BootstrapExtension {
    /**
     * Called once at builder construction time. Implementations may call any of the
     * builder's registration methods and may run synchronous, idempotent init work
     * (e.g. native lib init) inline. Heavy work belongs in a [BackgroundTask] registered
     * via `builder.background(...)`.
     */
    fun configure(builder: GeoVaultAppBootstrap.Builder, application: Application)
}

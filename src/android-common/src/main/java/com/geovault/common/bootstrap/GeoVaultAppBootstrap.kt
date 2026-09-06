package com.geovault.common.bootstrap

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.tasks.AuthStateWarmup
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level bootstrap helper for every GeoVault Android app.
 *
 * One fluent block in `Application.onCreate` describes:
 *  - auth init (redirect URI + client ID + auth-failure listener),
 *  - subsystem extensions (e.g. maps),
 *  - gate / background tasks,
 *  - reset hooks.
 *
 * Construction is via [builder]. After [Builder.build] returns, call [boot] from
 * `Application.onCreate` to drive the cold start. Observe [isReady] from
 * `Activity.onCreate` (typically via [com.geovault.common.ui.splash.GeoVaultSplashScreen])
 * to hold the splash until preload completes.
 *
 * The internal orchestrator is a [GeoVaultColdStart]; this class is the public-facing
 * surface that wires it up declaratively.
 */
class GeoVaultAppBootstrap private constructor(
    private val coldStart: GeoVaultColdStart,
) {

    /** Mirrors [GeoVaultColdStart.isReady]. */
    val isReady: StateFlow<Boolean> get() = coldStart.isReady

    /**
     * Drive the cold start. Must be called from `Application.onCreate` after `super.onCreate()`.
     * Idempotent.
     */
    fun boot(context: Context) {
        coldStart.boot(context)
    }

    /**
     * Builder for [GeoVaultAppBootstrap]. Each `app : Application.onCreate()` should
     * construct exactly one instance via [GeoVaultAppBootstrap.builder].
     *
     * Methods are chainable and order-independent for registration; the underlying
     * [GeoVaultColdStart] runs gates in parallel and background tasks fire-and-forget.
     */
    class Builder internal constructor(
        private val application: Application,
    ) {
        private val gateTasks = mutableListOf<GateTask>()
        private val backgroundTasks = mutableListOf<BackgroundTask>()

        /**
         * Initialize the auth subsystem and register a gate that warms
         * [AuthStateCache] from the provided controller.
         *
         * Calling [auth] is effectively mandatory for any production app — every screen
         * branches on auth state on first frame — but is left optional so unit-test
         * harnesses can spin up a bootstrap without OAuth wiring.
         *
         * @param redirectUri OAuth redirect URI for this app.
         * @param clientId OAuth client ID for this app.
         * @param authFailureListener Hook invoked when a token request fails irrecoverably.
         * @param authControllerProvider Lazily resolves the app's `CommonInitialAuthController`
         *   from the application context. Resolution happens inside the gate task.
         */
        fun auth(
            redirectUri: String,
            clientId: String,
            authFailureListener: GeoVaultAuthSession.AuthFailureListener,
            authControllerProvider: (Context) -> CommonInitialAuthController,
        ): Builder = apply {
            GeoVaultAuthSession.create(
                context = application,
                config = GeoVaultAuthSession.OAuthConfig(
                    clientId = clientId,
                    redirectUri = redirectUri,
                ),
                listener = authFailureListener,
            )
            gate(AuthStateWarmup(authControllerProvider))
        }

        /**
         * Install a [BootstrapExtension]. The extension's [BootstrapExtension.configure]
         * runs immediately and may register gates, background tasks, and reset hooks
         * via this same builder.
         */
        fun install(extension: BootstrapExtension): Builder = apply {
            extension.configure(this, application)
        }

        /** Register a [GateTask] that must complete before the splash dismisses. */
        fun gate(task: GateTask): Builder = apply {
            gateTasks += task
        }

        fun gate(id: String, block: suspend (Context) -> Unit): Builder = gate(
            object : GateTask(id) {
                override suspend fun execute(context: Context) = block(context)
            }
        )

        /** Register a [BackgroundTask] that runs fire-and-forget after gates complete. */
        fun background(task: BackgroundTask): Builder = apply {
            backgroundTasks += task
        }

        fun background(id: String, block: suspend (Context) -> Unit): Builder = background(
            object : BackgroundTask(id) {
                override suspend fun execute(context: Context) = block(context)
            }
        )

        /**
         * Register an [AppResetFlow] hook. Sugar around
         * [AppResetFlow.registerHook] that keeps every "happens at boot" registration
         * in one place for easy auditing.
         */
        fun resetHook(
            key: String,
            phase: AppResetFlow.Phase,
            reasons: Set<AppResetFlow.Reason> = AppResetFlow.Reason.entries.toSet(),
            order: Int = 0,
            action: (Context) -> Unit,
        ): Builder = apply {
            AppResetFlow.registerHook(
                key = key,
                phase = phase,
                order = order,
                reasons = reasons,
                action = action,
            )
        }

        /** Finalize and return the configured [GeoVaultAppBootstrap]. */
        fun build(): GeoVaultAppBootstrap {
            gate(com.geovault.common.bootstrap.tasks.ClearStaleUpdateCaches())
            val coldStart = GeoVaultColdStart(
                gates = gateTasks.toList(),
                background = backgroundTasks.toList(),
            )
            return GeoVaultAppBootstrap(coldStart)
        }
    }

    companion object {
        /** Start a new bootstrap configuration for the given application instance. */
        fun builder(application: Application): Builder = Builder(application)
    }
}

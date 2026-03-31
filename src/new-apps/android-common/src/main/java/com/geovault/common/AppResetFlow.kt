package com.geovault.common

import android.content.Context
import android.content.Intent
import android.util.Log

object AppResetFlow {
    private const val TAG = "AppResetFlow"

    enum class Reason {
        AUTH_FAILURE,
        MANUAL_SIGN_OUT
    }

    enum class Phase {
        BEFORE_TOKEN_CLEAR,
        AFTER_TOKEN_CLEAR,
        BEFORE_RELAUNCH
    }

    private data class ResetHook(
        val key: String,
        val phase: Phase,
        val order: Int,
        val reasons: Set<Reason>,
        val action: (Context) -> Unit
    )

    private val hooksByKey = LinkedHashMap<String, ResetHook>()

    @Synchronized
    fun registerHook(
        key: String,
        phase: Phase,
        order: Int = 0,
        reasons: Set<Reason> = Reason.entries.toSet(),
        action: (Context) -> Unit
    ) {
        require(key.isNotBlank()) { "Hook key must not be blank" }
        hooksByKey[key] = ResetHook(key, phase, order, reasons, action)
    }

    fun execute(
        context: Context,
        reason: Reason,
        mainActivityClass: Class<*>,
        configureRelaunchIntent: (Intent) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        runPhaseHooks(appContext, reason, Phase.BEFORE_TOKEN_CLEAR)
        GeovaultAuthManager.clearTokens(appContext)
        runPhaseHooks(appContext, reason, Phase.AFTER_TOKEN_CLEAR)
        runPhaseHooks(appContext, reason, Phase.BEFORE_RELAUNCH)

        val intent = Intent(appContext, mainActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        safeRun("configureRelaunchIntent") { configureRelaunchIntent(intent) }
        appContext.startActivity(intent)
    }

    private fun runPhaseHooks(context: Context, reason: Reason, phase: Phase) {
        val snapshot = synchronized(this) {
            hooksByKey.values
                .filter { hook -> hook.phase == phase && hook.reasons.contains(reason) }
                .sortedWith(compareBy<ResetHook> { it.order }.thenBy { it.key })
        }
        snapshot.forEach { hook -> safeRun("hook:${hook.key}") { hook.action(context) } }
    }

    private inline fun safeRun(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Reset step failed: $step", e)
        }
    }
}

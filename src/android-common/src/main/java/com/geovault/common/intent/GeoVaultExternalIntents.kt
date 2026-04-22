package com.geovault.common.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * Helpers for launching external-app intents with consistent error handling. Keeps per-app
 * code free of repeated `try { startActivity(...) } catch (ActivityNotFoundException) { ... }`.
 */
object GeoVaultExternalIntents {

    /**
     * Launch an [Intent.ACTION_VIEW] for [uri] (typically a `geo:` or `https://` maps URL).
     *
     * Returns `true` when the launch succeeded; returns `false` and invokes [onUnavailable]
     * when no map-capable activity is installed. Callers typically surface a snackbar from
     * [onUnavailable] so the user knows why nothing happened.
     */
    fun launchMap(activity: Activity, uri: Uri, onUnavailable: () -> Unit): Boolean {
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
            false
        }
    }
}

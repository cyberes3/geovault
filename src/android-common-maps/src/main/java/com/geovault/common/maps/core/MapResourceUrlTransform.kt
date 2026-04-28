package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.maplibre.android.storage.FileSource
import org.maplibre.android.storage.Resource

/**
 * Engine-level URL gate installed via [FileSource.setResourceTransform].
 *
 * MapLibre's `HttpRequestImpl.executeRequest` does a bare `return` when
 * `okhttp3.HttpUrl.parse` fails (see external sources). The native engine
 * then waits indefinitely for a completion that never arrives, which is
 * the root cause of intermittent "map didn't load" reports.
 *
 * Responsibilities:
 *
 *  1. **Veto invalid URLs.** Any blank / whitespace / unparseable URL is
 *     rewritten to a deterministic [REJECTED_HOST] sentinel so the request
 *     fails *fast* and *cleanly* via the auth client's
 *     `RejectedSentinelInterceptor` instead of stalling.
 *  2. **Centralize server-relative rewrites.** A leading `/api/tiles/...`
 *     path is rewritten to the absolute GeoVault server URL exactly once,
 *     in the only place that has all the context — the engine's request
 *     pipeline. No more string-replacing the raw style JSON.
 *
 * The native callback runs on the main thread (`@UiThread`); all logic
 * here is non-blocking.
 */
internal class MapResourceUrlTransform(
    context: Context,
) : FileSource.ResourceTransformCallback {

    private val appContext = context.applicationContext

    override fun onURL(@Resource.Kind kind: Int, url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return rejectAndLog(kind, url, REASON_EMPTY)
        }

        val rewritten = rewriteServerRelative(trimmed)

        if (rewritten.toHttpUrlOrNull() == null) {
            return rejectAndLog(kind, rewritten, REASON_UNPARSEABLE)
        }

        return rewritten
    }

    private fun rewriteServerRelative(url: String): String {
        if (!url.startsWith("/")) return url
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
        if (serverUrl.isEmpty()) return url
        return "$serverUrl$url"
    }

    private fun rejectAndLog(kind: Int, original: String, reason: String): String {
        val kindName = kindName(kind)
        Log.e(
            TAG,
            "Vetoed MapLibre resource URL: kind=$kindName reason=$reason " +
                "urlPreview=${original.take(URL_LOG_PREVIEW_MAX)}",
        )
        return "$REJECTED_URL_PREFIX$kindName"
    }

    companion object {
        private const val TAG = "MapResourceUrlTransform"
        private const val URL_LOG_PREVIEW_MAX = 80

        const val REASON_EMPTY = "empty"
        const val REASON_UNPARSEABLE = "unparseable"

        /**
         * `.invalid` is reserved by RFC 6761; using it guarantees no real
         * host can ever match this sentinel.
         */
        const val REJECTED_HOST = "maplibre-rejected.invalid"
        private const val REJECTED_URL_PREFIX = "https://$REJECTED_HOST/"

        /** Used by [RejectedSentinelInterceptor] to short-circuit these. */
        fun isRejectedSentinel(url: HttpUrl): Boolean = url.host == REJECTED_HOST

        fun kindName(kind: Int): String = when (kind) {
            Resource.UNKNOWN -> "unknown"
            Resource.STYLE -> "style"
            Resource.SOURCE -> "source"
            Resource.TILE -> "tile"
            Resource.GLYPHS -> "glyphs"
            Resource.SPRITE_IMAGE -> "spriteImage"
            Resource.SPRITE_JSON -> "spriteJson"
            // Native enum has Image=7; the Java IntDef is missing it.
            7 -> "image"
            else -> "kind-$kind"
        }
    }
}

package com.geovault.common.intent

import android.content.Intent
import android.net.Uri

/**
 * Extracts content/file URIs from incoming [Intent.ACTION_VIEW], [Intent.ACTION_SEND], and
 * [Intent.ACTION_SEND_MULTIPLE] intents. Unrelated actions (launcher MAIN, OAuth VIEW with a
 * custom scheme, …) yield an empty list.
 */
object GeoVaultIncomingFileIntents {

    fun isIncomingFileAction(intent: Intent?): Boolean {
        if (intent == null) return false
        return when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
            Intent.ACTION_VIEW -> isOpenableFileUri(intent.data)
            else -> false
        }
    }

    fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val collected = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf(::isOpenableFileUri))
            Intent.ACTION_SEND -> sendUris(intent)
            Intent.ACTION_SEND_MULTIPLE -> sendMultipleUris(intent)
            else -> emptyList()
        }
        return distinctPreserveOrder(collected)
    }

    /**
     * Clears the incoming-file payload so a later [android.app.Activity.onCreate] / rotation
     * does not re-process the same share.
     */
    fun consume(intent: Intent?) {
        if (intent == null) return
        intent.action = null
        intent.data = null
        intent.clipData = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_HTML_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
    }

    private fun sendUris(intent: Intent): List<Uri> {
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null) return listOf(stream)
        val fromClip = clipUris(intent)
        if (fromClip.isNotEmpty()) return fromClip
        return listOfNotNull(intent.data?.takeIf(::isOpenableFileUri))
    }

    private fun sendMultipleUris(intent: Intent): List<Uri> {
        val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (!streams.isNullOrEmpty()) return streams.filterNotNull()
        return clipUris(intent)
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return buildList {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(::add)
            }
        }
    }

    private fun isOpenableFileUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme ?: return false
        return scheme.equals("content", ignoreCase = true) ||
            scheme.equals("file", ignoreCase = true)
    }

    private fun distinctPreserveOrder(uris: List<Uri>): List<Uri> {
        val seen = LinkedHashSet<Uri>()
        uris.forEach { seen.add(it) }
        return seen.toList()
    }
}

package com.geovault.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper to copy text to the clipboard with reduced first-tap lag on devices
 * (e.g. Samsung) where the clipboard service is costly on first use.
 *
 * - Call [prewarm] once when the UI is attached so the first copy is faster.
 * - Call [copyText] from a click handler; pass a [CoroutineScope] (e.g. [lifecycleScope])
 *   so the copy runs off the main thread and avoids duplicate writes for the same text.
 */
class ClipboardCopyHelper(context: Context) {

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var lastCopiedText: String? = null

    /**
     * Warms the clipboard service on the main thread after [view] is attached.
     * Call once when your screen/fragment view is ready (e.g. in onCreateView after inflate).
     */
    fun prewarm(view: View) {
        view.post {
            clipboardManager?.hasPrimaryClip()
        }
    }

    /**
     * Copies [text] to the clipboard on a background thread, and skips the write
     * if [text] equals the last copied value. Call from a click listener using
     * a [CoroutineScope] (e.g. fragment's lifecycleScope).
     *
     * @param scope CoroutineScope to launch the copy (e.g. lifecycleScope).
     * @param text  Non-empty text to copy.
     * @param label Optional label for the clip; null is fine for plain text.
     * @return true if a copy was performed, false if skipped (blank or duplicate).
     */
    fun copyText(scope: CoroutineScope, text: String, label: String? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == lastCopiedText) return false
        scope.launch {
            withContext(Dispatchers.Default) {
                clipboardManager?.setPrimaryClip(ClipData.newPlainText(label, trimmed))
            }
            lastCopiedText = trimmed
        }
        return true
    }
}

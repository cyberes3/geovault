package com.geovault.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Shared clipboard helper for GeoVault apps.
 *
 * Centralizing copy logic keeps behavior consistent (trim input, skip duplicate copies,
 * and provide lightweight clipboard service prewarm).
 */
class ClipboardCopyHelper(context: Context) {
    private val clipboardManager: ClipboardManager? =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var lastCopiedText: String? = null

    fun prewarm() {
        clipboardManager?.hasPrimaryClip()
    }

    fun copyText(text: String, label: String = "Text"): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == lastCopiedText) return false
        clipboardManager?.setPrimaryClip(ClipData.newPlainText(label, trimmed))
        lastCopiedText = trimmed
        return true
    }

    fun copyTextWithToast(
        context: Context,
        text: String,
        label: String = "Text",
        toastMessage: String,
    ): Boolean {
        if (!copyText(text = text, label = label)) return false
        Toast.makeText(context.applicationContext, toastMessage, Toast.LENGTH_SHORT).show()
        return true
    }
}


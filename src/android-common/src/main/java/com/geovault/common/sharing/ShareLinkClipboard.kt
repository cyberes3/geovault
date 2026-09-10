package com.geovault.common.sharing

import android.content.Context
import com.geovault.common.util.ClipboardCopyHelper

/**
 * Copies a share URL to the clipboard. Distinct from incoming-file share sessions.
 */
class ShareLinkClipboard(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard = ClipboardCopyHelper(appContext)

    fun copyRelativePath(origin: String, relativePath: String, toastMessage: String): Boolean {
        return clipboard.copyTextWithToast(
            context = appContext,
            text = ShareUrl.absolute(origin, relativePath),
            label = "Share link",
            toastMessage = toastMessage,
        )
    }

    fun copyText(text: String, toastMessage: String): Boolean {
        return clipboard.copyTextWithToast(
            context = appContext,
            text = text,
            label = "Share link",
            toastMessage = toastMessage,
        )
    }
}

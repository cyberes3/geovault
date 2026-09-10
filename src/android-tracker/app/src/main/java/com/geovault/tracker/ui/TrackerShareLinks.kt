package com.geovault.tracker.ui

import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.sharing.ShareLinkClipboard
import com.geovault.common.sharing.ShareUrl

object TrackerShareLinks {
    fun copy(context: Context, shareUrl: String?, toastMessage: String) {
        if (shareUrl.isNullOrBlank()) return
        ShareLinkClipboard(context).copyRelativePath(
            origin = GeoVaultAuthSession.get().getServerUrl(),
            relativePath = shareUrl,
            toastMessage = toastMessage,
        )
    }

    fun absoluteOrNull(shareUrl: String?): String? {
        if (shareUrl.isNullOrBlank()) return null
        return ShareUrl.absolute(GeoVaultAuthSession.get().getServerUrl(), shareUrl)
    }
}

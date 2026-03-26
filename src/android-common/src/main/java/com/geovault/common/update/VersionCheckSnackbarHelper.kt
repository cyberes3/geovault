package com.geovault.common.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.geovault.common.ImportantMessageSnackbar

object VersionCheckSnackbarHelper {
    fun showIfUpdateAvailable(
        context: Context,
        snackbar: ImportantMessageSnackbar?,
        result: VersionCheckResult
    ): Boolean {
        val updateResult = result as? VersionCheckResult.UpdateAvailable ?: return false
        val targetSnackbar = snackbar ?: run {
            Log.w(UpdateCheckLog.TAG, "snackbar: update available but snackbar view is null")
            return false
        }

        Log.i(
            UpdateCheckLog.TAG,
            "snackbar: showing update prompt for ${updateResult.appName} ${updateResult.versionLabel}"
        )
        val message = "A newer ${updateResult.appName} version is available (${updateResult.versionLabel})."
        targetSnackbar.showMessage(message, ACTION_LABEL) {
            Log.d(UpdateCheckLog.TAG, "snackbar: user tapped Open → ${updateResult.releaseUrl}")
            openReleaseInBrowser(context, updateResult.releaseUrl)
        }
        return true
    }

    fun openReleaseInBrowser(context: Context, releaseUrl: String) {
        val uri = Uri.parse(releaseUrl)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
            Log.d(UpdateCheckLog.TAG, "opened release URL in Custom Tabs")
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "Custom Tabs failed, falling back to ACTION_VIEW: ${e.message}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private const val ACTION_LABEL = "Open"
}

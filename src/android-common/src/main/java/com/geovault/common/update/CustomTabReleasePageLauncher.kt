package com.geovault.common.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent

class CustomTabReleasePageLauncher(
    private val context: Context
) : ReleasePageLauncher {

    override fun openReleasePage(releaseUrl: String) {
        Log.d(UpdateCheckLog.TAG, "snackbar: user tapped Open → $releaseUrl")
        val uri = Uri.parse(releaseUrl)
        val launchInNewTask = context !is Activity
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            if (launchInNewTask) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, uri)
            Log.d(UpdateCheckLog.TAG, "opened release URL in Custom Tabs")
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "Custom Tabs failed, falling back to ACTION_VIEW: ${e.message}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (launchInNewTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

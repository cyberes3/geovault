package com.geovault.uploader

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.geovault.common.GeovaultAuthManager

private const val PREFS_NAME = "geovault_prefs"

/**
 * On auth failure (401): clear all app data and return to initial state (Settings / login).
 * Uploader has no offline data to export.
 */
fun resetOnAuthFailure(activity: Activity) {
    val context = activity.applicationContext
    GeovaultAuthManager.clearTokens(context)
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    val main = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    context.startActivity(main)
    activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    activity.finishAffinity()
}

package com.geovault.places

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.geovault.common.GeovaultAuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "geovault_prefs"
private const val PENDING_NAVIGATION_IDS_KEY = "pending_navigation_ids"

const val EXTRA_SHOW_EXPORT_SAVED_MESSAGE = "show_export_saved_message"

private fun exportFilename(): String =
    "geovault_emergency_export_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.txt"

private fun formatPlaceBlock(feature: Feature, source: String): String {
    val p = feature.properties
    val coords = feature.geometry.coordinates
    val coordsLine = if (coords.size >= 2) "${coords[1]}, ${coords[0]}" else ""
    val addressLine = p.address?.takeIf { it.isNotBlank() } ?: ""
    val descLine = p.description?.takeIf { it.isNotBlank() } ?: ""
    val other = buildList {
        if (p.database_id != null) add("database_id: ${p.database_id}")
        add(source)
    }.joinToString(" | ")
    return buildString {
        appendLine(p.name?.takeIf { it.isNotBlank() } ?: "(unnamed)")
        appendLine(p.created_at ?: "")
        appendLine(coordsLine)
        appendLine(addressLine)
        appendLine(descLine)
        appendLine(other)
        appendLine()
    }
}

/**
 * On auth failure (401): save unsynced offline/cached data to Files/Downloads as a TXT file, then clear all app
 * data and return to initial state. MainActivity shows a Toast when it receives EXTRA_SHOW_EXPORT_SAVED_MESSAGE.
 */
fun exportThenResetOnAuthFailure(activity: Activity) {
    val context = activity.applicationContext
    val cache = (context as PlacesApplication).placesCache
    val offlineList = cache.getOfflineFeatures()
    val cachedFeatures = cache.getCachedFeatures()
    val hasData = offlineList.isNotEmpty() || cachedFeatures.isNotEmpty()

    var exportSaved = false
    if (hasData) {
        try {
            val sb = StringBuilder()
            val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            sb.appendLine("GeoVault emergency export — $exportedAt")
            sb.appendLine()
            for (offline in offlineList) {
                sb.append(formatPlaceBlock(offline.feature, "offline"))
            }
            val cachedFiltered = cachedFeatures.filter { c ->
                offlineList.none { it.feature.properties.database_id == c.properties.database_id }
            }
            for (feature in cachedFiltered) {
                sb.append(formatPlaceBlock(feature, "cached"))
            }
            val txtBytes = sb.toString().toByteArray(Charsets.UTF_8)
            val filename = exportFilename()
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(txtBytes) }
                exportSaved = true
            }
        } catch (e: Exception) {
            // Proceed to reset even if export fails
        }
    }

    GeovaultAuthManager.clearTokens(context)
    cache.clear()
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .remove(PENDING_NAVIGATION_IDS_KEY)
        .apply()

    val main = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (exportSaved) {
        main.putExtra(EXTRA_SHOW_EXPORT_SAVED_MESSAGE, true)
    }
    context.startActivity(main)
    activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    activity.finishAffinity()
}

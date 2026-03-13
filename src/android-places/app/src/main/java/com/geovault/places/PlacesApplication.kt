package com.geovault.places

import android.app.Application
import android.content.Context
import android.util.Log
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.MapLibreInitializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlacesApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    val placesCache: PlacesCache by lazy { PlacesCache(applicationContext) }

    companion object {
        private const val TAG = "GeoVaultMap"
    }
    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(this, "com.geovault.places://oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES)
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(applicationContext)
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onAuthFailure(context: Context) {
        Log.w(TAG, "Unrecoverable auth failure detected. Resetting app.")
        
        // 1. Emergency Export of unsynced data
        performEmergencyExport(context)

        // 2. Clear tokens
        GeovaultAuthManager.clearTokens(context)
        
        // 3. Clear cache
        placesCache.clear()
        
        // 4. Clear app-specific prefs (matches AppResetHelper logic)
        context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .remove("pending_navigation_ids")
            .apply()

        // 5. Return to login screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Signal to MainActivity that an export might have been saved
            putExtra(MainActivity.EXTRA_SHOW_EXPORT_SAVED_MESSAGE, true)
        }
        context.startActivity(intent)
    }

    private fun performEmergencyExport(context: Context) {
        val offlineList = placesCache.getOfflineFeatures()
        val cachedFeatures = placesCache.getCachedFeatures()
        val hasData = offlineList.isNotEmpty() || cachedFeatures.isNotEmpty()

        if (!hasData) return

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
            val filename = "geovault_emergency_export_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.txt"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(txtBytes) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency export failed", e)
        }
    }

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

}

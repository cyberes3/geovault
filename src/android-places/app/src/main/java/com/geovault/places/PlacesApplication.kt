package com.geovault.places

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.core.GeoVaultMainMapControllerStore
import com.geovault.places.BuildConfig
import com.geovault.places.di.PlacesAppServices
import com.geovault.common.maps.core.MapLibreInitializer
import com.geovault.places.model.Feature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlacesApplication : Application(), GeovaultAuthManager.AuthFailureListener {
    companion object {
        private const val HOOK_EMERGENCY_EXPORT = "places_emergency_export"
        private const val HOOK_CLEAR_LOCAL = "places_clear_local"
    }

    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(
            context = this,
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
            clientId = GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES,
        )
        GeovaultAuthManager.setAuthFailureListener(this)
        val services = PlacesAppServices.from(this)
        services.cacheStore().preloadOnLaunch()
        services.navigationRepository().preloadOnLaunch()
        MapLibreInitializer.init(this)
        AppResetFlow.registerHook(
            key = HOOK_EMERGENCY_EXPORT,
            phase = AppResetFlow.Phase.BEFORE_EMERGENCY_EXPORT,
            reasons = setOf(AppResetFlow.Reason.AUTH_FAILURE),
        ) { hookContext ->
            performEmergencyExport(hookContext)
        }
        AppResetFlow.registerHook(
            key = HOOK_CLEAR_LOCAL,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) { hookContext ->
            GeoVaultMainMapControllerStore.forceReleaseKeyForReset(PLACES_MAIN_MAP_KEY)
            PlacesAppServices.from(this).cacheStore().clear()
            PlacesAppServices.from(this).navigationRepository().clearPending()
        }
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
            configureRelaunchIntent = { intent ->
                intent.putExtra(MainActivity.EXTRA_SHOW_EXPORT_SAVED_MESSAGE, true)
            },
        )
    }

    private fun performEmergencyExport(context: Context) {
        val services = PlacesAppServices.from(this)
        val offlineList = services.cacheStore().getOfflineFeatures()
        val cachedFeatures = services.cacheStore().getCachedFeatures()
        if (offlineList.isEmpty() && cachedFeatures.isEmpty()) return

        runCatching {
            val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val content = buildString {
                appendLine("GeoVault emergency export - $exportedAt")
                appendLine()
                offlineList.forEach { append(formatPlaceBlock(it.feature)) }
                val cachedFiltered = cachedFeatures.filter { cached ->
                    offlineList.none { it.feature.properties.database_id == cached.properties.database_id }
                }
                cachedFiltered.forEach { append(formatPlaceBlock(it)) }
            }

            val filename = "geovault_emergency_export_${
                SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            }.txt"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun formatPlaceBlock(feature: Feature): String {
        val properties = feature.properties
        val coords = feature.geometry.coordinates
        val coordsLine = if (coords.size >= 2) "${coords[1]}, ${coords[0]}" else ""
        val addressLine = properties.address?.takeIf { it.isNotBlank() } ?: ""
        val descLine = properties.description?.takeIf { it.isNotBlank() } ?: ""
        return buildString {
            appendLine(properties.name?.takeIf { it.isNotBlank() } ?: "(unnamed)")
            appendLine(properties.created_at ?: "")
            appendLine(coordsLine)
            appendLine(addressLine)
            appendLine(descLine)
            appendLine()
        }
    }
}

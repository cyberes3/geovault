package com.geovault.places

import android.app.Application
import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.files.GeoVaultExportFileNames
import com.geovault.common.files.GeoVaultFileExport
import com.geovault.common.geo.CoordinateParser
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.common.maps.bootstrap.GeoVaultMapsBootstrap
import com.geovault.places.BuildConfig
import com.geovault.places.data.PlacesApiFactory
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class PlacesApplication : Application(), GeoVaultAuthSession.AuthFailureListener {
    companion object {
        private const val HOOK_EMERGENCY_EXPORT = "places_emergency_export"
        private const val HOOK_CLEAR_LOCAL = "places_clear_local"

        private val pendingExportSavedToast = AtomicBoolean(false)

        fun consumePendingExportSavedToast(): Boolean = pendingExportSavedToast.getAndSet(false)
    }

    lateinit var bootstrap: GeoVaultAppBootstrap
        private set

    override fun onCreate() {
        super.onCreate()
        GeoVaultAppVersionLog.log(this, BuildConfig.GIT_COMMIT_SHA)
        bootstrap = GeoVaultAppBootstrap.builder(this)
            .auth(
                redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
                clientId = GeoVaultAuthSession.OAUTH_CLIENT_ID_PLACES,
                authFailureListener = this,
            ) { ctx -> PlacesAppServices.from(ctx).initialAuthController() }
            .install(GeoVaultMapsBootstrap(PLACES_MAIN_MAP_KEY, prewarmMainMap = false))
            .gate("places-store") { ctx ->
                val services = PlacesAppServices.from(ctx)
                services.placesStore().preloadOnLaunch()
                services.navigationRepository().preloadOnLaunch()
            }
            .resetHook(
                key = HOOK_EMERGENCY_EXPORT,
                phase = AppResetFlow.Phase.BEFORE_EMERGENCY_EXPORT,
                reasons = setOf(
                    AppResetFlow.Reason.AUTH_FAILURE,
                    AppResetFlow.Reason.MANUAL_SIGN_OUT,
                ),
            ) { hookContext ->
                performEmergencyExport(hookContext)
            }
            .resetHook(
                key = HOOK_CLEAR_LOCAL,
                phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
            ) { _ ->
                PlacesAppServices.from(this).placesStore().clear()
                PlacesAppServices.from(this).navigationRepository().clearPending()
                PlacesApiFactory.clearCache()
            }
            .build()
        bootstrap.boot(this)
    }

    override fun onAuthFailure(context: Context) {
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java,
            configureRelaunchIntent = { intent ->
                if (pendingExportSavedToast.get()) {
                    intent.putExtra(MainActivity.EXTRA_SHOW_EXPORT_SAVED_MESSAGE, true)
                }
            },
        )
    }

    private fun performEmergencyExport(context: Context) {
        val services = PlacesAppServices.from(this)
        val offlineList = services.placesStore().getOfflineFeatures()
        val cachedFeatures = services.placesStore().getCachedFeatures()
        if (offlineList.isEmpty() && cachedFeatures.isEmpty()) {
            pendingExportSavedToast.set(false)
            return
        }

        val wrote = runCatching {
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
            val filename = "${GeoVaultExportFileNames.timestamped("geovault_emergency_export")}.txt"
            runBlocking {
                GeoVaultFileExport(context).saveToDownloads(
                    displayName = filename,
                    mimeType = "text/plain",
                    bytes = content.toByteArray(Charsets.UTF_8),
                    showToast = false,
                ).isSuccess
            }
        }.getOrDefault(false)
        pendingExportSavedToast.set(wrote)
    }

    private fun formatPlaceBlock(feature: Feature): String {
        val properties = feature.properties
        val coords = feature.geometry.coordinates
        val coordsLine = if (coords.size >= 2) {
            CoordinateParser.formatLatLon(coords[1], coords[0])
        } else {
            ""
        }
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

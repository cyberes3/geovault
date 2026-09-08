package com.geovault.places

import android.app.Application
import android.content.Context
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.GeoVaultAppBootstrap
import com.geovault.common.files.GeoVaultExportFileNames
import com.geovault.common.files.GeoVaultFileExport
import com.geovault.common.logging.GeoVaultAppVersionLog
import com.geovault.common.maps.bootstrap.GeoVaultMapsBootstrap
import com.geovault.places.BuildConfig
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.export.PlacesExportFormat
import com.geovault.places.export.PlacesExporter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class PlacesApplication : Application(), GeoVaultAuthSession.AuthFailureListener {
    companion object {
        private const val HOOK_EMERGENCY_EXPORT = "places_emergency_export"
        private const val HOOK_CLEAR_LOCAL = "places_clear_local"

        private val pendingExportSavedToast = AtomicBoolean(false)

        fun consumePendingExportSavedToast(): Boolean = pendingExportSavedToast.getAndSet(false)
    }

    lateinit var services: PlacesAppServices
        private set

    lateinit var bootstrap: GeoVaultAppBootstrap
        private set

    private val skipLocalClearAfterFailedExport = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        GeoVaultAppVersionLog.log(this, BuildConfig.GIT_COMMIT_SHA)
        services = PlacesAppServices(this)
        bootstrap = GeoVaultAppBootstrap.builder(this)
            .auth(
                redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback",
                clientId = GeoVaultAuthSession.OAUTH_CLIENT_ID_PLACES,
                authFailureListener = this,
            ) { services.initialAuthController() }
            .install(GeoVaultMapsBootstrap(PLACES_MAIN_MAP_KEY, prewarmMainMap = false))
            .gate("places-store") {
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
                if (skipLocalClearAfterFailedExport.getAndSet(false)) return@resetHook
                services.placesStore().clearBlocking()
                services.navigationRepository().clearPendingBlocking()
                services.placesRepository().clearApiCache()
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
        val places = PlacesListProjection.exportable(services.placesStore().places())
        if (places.isEmpty()) {
            pendingExportSavedToast.set(false)
            skipLocalClearAfterFailedExport.set(false)
            return
        }
        val wrote = runCatching {
            val content = PlacesExporter.export(places, PlacesExportFormat.PLAIN_TEXT)
            val filename = "${GeoVaultExportFileNames.timestamped("geovault_emergency_export")}.txt"
            runBlocking {
                GeoVaultFileExport(context).saveToDownloads(
                    displayName = filename,
                    mimeType = "text/plain",
                    bytes = content,
                    showToast = false,
                ).isSuccess
            }
        }.getOrDefault(false)
        pendingExportSavedToast.set(wrote)
        skipLocalClearAfterFailedExport.set(!wrote)
    }
}

package com.geovault.uploader.di

import android.app.Application
import android.content.Context
import com.geovault.common.ServerUrlContract
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeovaultAuthServices
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.uploader.data.AuthRepository
import com.geovault.uploader.data.UploaderPreferences
import com.geovault.uploader.data.UploadRepository
import com.geovault.uploader.data.ValidationRepository
import com.geovault.uploader.domain.ImportUploadQueue
import com.geovault.uploader.domain.PickerSelectionRouter

/**
 * Composition root for app-layer dependencies.
 * Keeps ViewModels focused on state orchestration rather than object construction.
 */
class UploaderAppServices private constructor(
    context: Context
) {
    private val appContext = context.applicationContext
    private val authServices by lazy { GeovaultAuthServices(appContext) }

    val uploaderPreferences: UploaderPreferences by lazy {
        UploaderPreferences.getInstance(appContext)
    }

    val openableUriMetadata: GeoVaultOpenableUriMetadata by lazy {
        GeoVaultOpenableUriMetadata(appContext.contentResolver)
    }

    val uploadRepository: UploadRepository by lazy {
        UploadRepository(
            context = appContext,
            contentResolver = appContext.contentResolver,
            serverConfigService = authServices,
            authSessionService = authServices,
        )
    }

    val validationRepository: ValidationRepository by lazy {
        ValidationRepository(
            context = appContext,
            serverConfigService = authServices,
            authSessionService = authServices,
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            serverConfigService = authServices,
            authSessionService = authServices,
            oauthPreparationService = authServices,
            peerServerUrlsProvider = {
                ServerUrlContract.getServerUrlsFromOtherApps(appContext)
            },
        )
    }

    val pickerSelectionRouter: PickerSelectionRouter by lazy {
        PickerSelectionRouter(openableUriMetadata::displayName)
    }

    val importUploadQueue: ImportUploadQueue by lazy {
        ImportUploadQueue(uploadRepository)
    }

    fun initialAuthController(
        invalidServerUrlMessage: String = "Server URL is required. Connect your account to sign in.",
        unreachableServerMessage: String = "Could not reach server. Check URL and connection.",
    ): CommonInitialAuthController = CommonInitialAuthController(
        serverConfigService = authServices,
        authSessionService = authServices,
        oauthPreparationService = authServices,
        peerServerUrlsProvider = { ServerUrlContract.getServerUrlsFromOtherApps(appContext) },
        invalidServerUrlMessage = invalidServerUrlMessage,
        unreachableServerMessage = unreachableServerMessage,
    )

    companion object {
        fun from(application: Application): UploaderAppServices {
            return UploaderAppServices(application.applicationContext)
        }
    }
}

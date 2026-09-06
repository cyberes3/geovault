package com.geovault.uploader.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.uploader.data.UploaderPreferences
import com.geovault.uploader.data.UploadRepository
import com.geovault.uploader.data.ValidationRepository
import com.geovault.common.files.GeoVaultFileIngest
import com.geovault.common.files.GeoVaultUploadFileTypes
import com.geovault.uploader.domain.ImportUploadQueue

/**
 * Composition root for app-layer dependencies.
 * Keeps ViewModels focused on state orchestration rather than object construction.
 */
class UploaderAppServices private constructor(
    context: Context
) {
    private val appContext = context.applicationContext

    fun authSession(): GeoVaultAuthSession = GeoVaultAuthSession.get()

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
            authSession = authSession(),
        )
    }

    val validationRepository: ValidationRepository by lazy {
        ValidationRepository(
            context = appContext,
            authSession = authSession(),
        )
    }

    val fileIngest: GeoVaultFileIngest by lazy {
        GeoVaultFileIngest(
            context = appContext,
            catalog = GeoVaultUploadFileTypes.catalog,
            stageLongLivedGrants = true,
        )
    }

    val importUploadQueue: ImportUploadQueue by lazy {
        ImportUploadQueue(uploadRepository)
    }

    fun initialAuthController(
        invalidServerUrlMessage: String = "Server URL is required. Connect your account to sign in.",
        unreachableServerMessage: String = "Could not reach server. Check URL and connection.",
    ): CommonInitialAuthController = CommonInitialAuthController.standard(
        session = authSession(),
        appContext = appContext,
        invalidServerUrlMessage = invalidServerUrlMessage,
        unreachableServerMessage = unreachableServerMessage,
    )

    companion object {
        fun from(application: Application): UploaderAppServices {
            return UploaderAppServices(application.applicationContext)
        }
    }
}

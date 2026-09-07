package com.geovault.uploader.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.common.update.GeoVaultAppUpdateCoordinator
import com.geovault.uploader.BuildConfig
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

    private val initialAuthController by lazy {
        CommonInitialAuthController.standard(
            session = authSession(),
            appContext = appContext,
            invalidServerUrlMessage = "Server URL is required. Connect your account to sign in.",
            unreachableServerMessage = "Could not reach server. Check URL and connection.",
        )
    }

    private val updateCoordinator by lazy {
        GeoVaultAndroidReleaseIdentity.Uploader.updateCoordinator(
            application = appContext as Application,
            localFullCommitSha = { BuildConfig.GIT_COMMIT_SHA },
        )
    }

    fun initialAuthController(): CommonInitialAuthController = initialAuthController

    fun updateCoordinator(): GeoVaultAppUpdateCoordinator = updateCoordinator

    companion object {
        @Volatile
        private var instance: UploaderAppServices? = null

        fun from(application: Application): UploaderAppServices {
            val context = application.applicationContext
            return instance ?: synchronized(this) {
                instance ?: UploaderAppServices(context).also { instance = it }
            }
        }
    }
}

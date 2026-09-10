package com.geovault.uploader.di

import android.app.Application
import android.content.Context
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.auth.GeoVaultConnectionValidator
import com.geovault.common.files.GeoVaultFileIngest
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.uploader.files.UploaderFileTypes
import com.geovault.common.intent.GeoVaultIncomingFileIntake
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.common.update.GeoVaultAppUpdateCoordinator
import com.geovault.uploader.BuildConfig
import com.geovault.uploader.data.UploadRepository
import com.geovault.uploader.data.UploaderSettingsStore
import com.geovault.uploader.domain.ImportUploadEngine

class UploaderAppServices private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun authSession(): GeoVaultAuthSession = GeoVaultAuthSession.get()

    private val settingsStore by lazy {
        UploaderSettingsStore(appContext)
    }

    private val openableUriMetadata by lazy {
        GeoVaultOpenableUriMetadata(appContext.contentResolver)
    }

    private val uploadRepository by lazy {
        UploadRepository.fromSession(
            contentResolver = appContext.contentResolver,
            session = authSession(),
        )
    }

    private val connectionValidator by lazy {
        GeoVaultConnectionValidator(authSession())
    }

    private val fileIngest by lazy {
        GeoVaultFileIngest(
            context = appContext,
            catalog = UploaderFileTypes.catalog,
            stageLongLivedGrants = true,
        )
    }

    private val incomingIntake by lazy {
        GeoVaultIncomingFileIntake(fileIngest)
    }

    private val importUploadEngine by lazy {
        ImportUploadEngine(uploadRepository)
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

    fun settingsStore(): UploaderSettingsStore = settingsStore

    fun openableUriMetadata(): GeoVaultOpenableUriMetadata = openableUriMetadata

    fun uploadRepository(): UploadRepository = uploadRepository

    fun connectionValidator(): GeoVaultConnectionValidator = connectionValidator

    fun incomingIntake(): GeoVaultIncomingFileIntake = incomingIntake

    fun importUploadEngine(): ImportUploadEngine = importUploadEngine

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

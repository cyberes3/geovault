package com.geovault.tracker.di

import android.app.Application
import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ServerUrlContract
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.auth.GeovaultAuthServices

class TrackerAppServices private constructor(private val appContext: Context) {

    private val authServices by lazy { GeovaultAuthServices(appContext) }

    private val authController by lazy {
        CommonInitialAuthController(
            serverConfigService = authServices,
            authSessionService = authServices,
            oauthPreparationService = authServices,
            peerServerUrlsProvider = { ServerUrlContract.getServerUrlsFromOtherApps(appContext) },
        )
    }

    fun initialAuthController(): CommonInitialAuthController = authController

    companion object {
        @Volatile
        private var instance: TrackerAppServices? = null

        fun from(application: Application): TrackerAppServices {
            return instance ?: synchronized(this) {
                instance ?: TrackerAppServices(application.applicationContext).also { instance = it }
            }
        }
    }
}

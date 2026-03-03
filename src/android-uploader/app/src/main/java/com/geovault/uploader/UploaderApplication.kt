package com.geovault.uploader

import android.app.Application
import com.geovault.common.GeovaultAuthManager

class UploaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(this, "com.geovault.uploader://oauth/callback")
        GeovaultAuthManager.fetchUserStatus(this)
    }
}

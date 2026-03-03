package com.geovault.places

import android.app.Application
import com.geovault.common.GeovaultAuthManager

class PlacesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GeovaultAuthManager.init(this, "com.geovault.places://oauth/callback")
        GeovaultAuthManager.fetchUserStatus(this)
    }
}

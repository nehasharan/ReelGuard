package com.reelguard.app

import android.app.Application

class ReelGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize any app-wide dependencies here
        // e.g., crash reporting, analytics, logging
    }
}

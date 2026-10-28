package com.fireplace

import android.app.Application

/**
 * Application entry point. Deliberately does nothing network- or telemetry-
 * related: no analytics init, no crash reporter, no remote config. Fireplace
 * uses INTERNET only for the one-time setup download; there is nothing to phone
 * home to, and inference and retrieval are fully offline.
 */
class FireplaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

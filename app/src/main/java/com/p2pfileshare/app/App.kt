package com.p2pfileshare.app

import android.app.Application
import android.os.Environment
import android.util.Log

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            instance = this
            // Pre-warm storage path - safe call
            val root = getStorageRoot()
            Log.d("App", "Storage root: $root")
        } catch (e: Exception) {
            Log.e("App", "Error initializing app", e)
        }
    }

    companion object {
        var instance: App? = null
            private set

        const val SERVICE_NAME_PREFIX = "P2PFileShare"
        const val SERVICE_TYPE = "_p2pfileshare._tcp."
        const val DEFAULT_PORT = 9527
        const val CHANNEL_ID = "p2p_fileshare_channel"
        const val NOTIFICATION_ID = 1001

        fun getStorageRoot(): String {
            return try {
                Environment.getExternalStorageDirectory().absolutePath
            } catch (e: Exception) {
                Log.e("App", "Failed to get storage root", e)
                "/sdcard"
            }
        }
    }
}

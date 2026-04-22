package com.p2pfileshare.app

import android.app.Application
import android.os.Environment
import android.util.Log

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            // Pre-warm storage path
            val root = getStorageRoot()
            Log.d("App", "Storage root: $root")
        } catch (e: Exception) {
            Log.e("App", "Error initializing storage path", e)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        const val SERVICE_NAME_PREFIX = "P2PFileShare"
        const val SERVICE_TYPE = "_p2pfileshare._tcp."
        const val DEFAULT_PORT = 9527
        const val CHANNEL_ID = "p2p_fileshare_channel"
        const val NOTIFICATION_ID = 1001

        fun getStorageRoot(): String {
            return Environment.getExternalStorageDirectory().absolutePath
        }
    }
}

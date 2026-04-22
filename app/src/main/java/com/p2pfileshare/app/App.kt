package com.p2pfileshare.app

import android.app.Application
import android.os.Environment

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
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

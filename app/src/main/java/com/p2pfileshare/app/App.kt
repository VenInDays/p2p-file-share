package com.p2pfileshare.app

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Environment
import android.text.format.Formatter
import android.util.Log
import com.p2pfileshare.app.security.SecurityManager
import java.net.Inet4Address
import java.net.NetworkInterface

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            instance = this
            val root = getStorageRoot()
            Log.d("App", "Storage root: $root")

            // Initialize security manager (AES-256, API tokens, rate limiting)
            SecurityManager.initialize(this)
            Log.d("App", "SecurityManager initialized")

            // Periodic cleanup of rate limit entries (every 5 minutes)
            val cleanupHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val cleanupRunnable = object : Runnable {
                override fun run() {
                    SecurityManager.cleanupRateLimits()
                    cleanupHandler.postDelayed(this, 5 * 60 * 1000L)
                }
            }
            cleanupHandler.postDelayed(cleanupRunnable, 5 * 60 * 1000L)

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

        fun getWifiIpAddress(): String {
            val ctx = instance ?: return "Unknown"

            try {
                val wifiManager = ctx.applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ip != 0) {
                    @Suppress("DEPRECATION")
                    return Formatter.formatIpAddress(ip)
                }
            } catch (e: Exception) {
                Log.e("App", "WifiManager IP lookup failed", e)
            }

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "Unknown"
                for (intf in interfaces) {
                    val name = intf.name.lowercase()
                    if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("rmnet")) {
                        continue
                    }
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: "Unknown"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("App", "NetworkInterface IP lookup failed", e)
            }

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "Unknown"
                for (intf in interfaces) {
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.0.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("App", "Last resort IP lookup failed", e)
            }

            return "Unknown"
        }
    }
}

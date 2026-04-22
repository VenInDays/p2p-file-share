package com.p2pfileshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.server.FileServer
import com.p2pfileshare.app.ui.MainActivity
import com.p2pfileshare.app.util.PreferencesManager
import kotlinx.coroutines.*

class P2PService : Service() {

    private val tag = "P2PService"
    private var fileServer: FileServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val prefs by lazy { PreferencesManager(this) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var discoveredPeers = mutableListOf<PeerDevice>()
    private var onPeerDiscovered: ((PeerDevice) -> Unit)? = null
    private var onPeerLost: ((String) -> Unit)? = null

    companion object {
        var isRunning = false
            private set
        var instance: P2PService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, P2PService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("P2PService", "Failed to start service", e)
                // Retry once
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e2: Exception) {
                    Log.e("P2PService", "Failed to start service on retry", e2)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, P2PService::class.java))
        }

        fun setPeerCallbacks(
            onDiscovered: ((PeerDevice) -> Unit)?,
            onLost: ((String) -> Unit)?
        ) {
            instance?.let { svc ->
                svc.onPeerDiscovered = onDiscovered
                svc.onPeerLost = onLost
            }
        }

        fun getDiscoveredPeers(): List<PeerDevice> {
            return instance?.discoveredPeers?.toList() ?: emptyList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // IMPORTANT: Must call startForeground immediately on Android 8+
        // otherwise app crashes with ForegroundServiceDidNotStartInTimeException
        val notification = buildNotification("P2P File Share đang chạy")
        startForegroundCompat(App.NOTIFICATION_ID, notification)

        scope.launch {
            try {
                startServer()
                registerService()
                startDiscovery()
            } catch (e: Exception) {
                Log.e(tag, "Error in service startup", e)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterService()
        stopDiscovery()
        stopServer()
        isRunning = false
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service when app is swiped away
        val restartIntent = Intent(this, P2PService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to restart service after task removed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Compatibility wrapper for startForeground() that works on all Android versions.
     * On Android 8+ (API 26+), we must show a notification with a channel.
     * On older versions, we use the deprecated Notification constructor.
     */
    private fun startForegroundCompat(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Can specify foreground service type
                // Use 0 for no specific type (general purpose)
                startForeground(id, notification, 0)
            } else {
                startForeground(id, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed, trying fallback", e)
            try {
                startForeground(id, notification)
            } catch (e2: Exception) {
                Log.e(tag, "startForeground fallback also failed", e2)
            }
        }
    }

    private fun startServer() {
        try {
            val port = prefs.serverPort
            fileServer = FileServer(port, prefs).also { server ->
                server.start()
            }
            Log.d(tag, "Server started on port ${fileServer?.listeningPort}")
            updateNotification("Đang chia sẻ trên port ${fileServer?.listeningPort}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server on port ${prefs.serverPort}", e)
            // Try with port 0 (auto-assign)
            try {
                fileServer = FileServer(0, prefs).also { server ->
                    server.start()
                }
                Log.d(tag, "Server started on alternative port ${fileServer?.listeningPort}")
                updateNotification("Đang chia sẻ trên port ${fileServer?.listeningPort}")
            } catch (e2: Exception) {
                Log.e(tag, "Failed to start server on alternative port", e2)
                updateNotification("Lỗi khởi động server")
            }
        }
    }

    private fun stopServer() {
        try {
            fileServer?.stop()
            fileServer = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping server", e)
        }
    }

    private fun registerService() {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "${App.SERVICE_NAME_PREFIX}_${prefs.serviceName}"
                serviceType = App.SERVICE_TYPE
                port = fileServer?.listeningPort ?: prefs.serverPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(tag, "Service registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(tag, "Service unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register NSD service", e)
        }
    }

    private fun unregisterService() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to unregister service", e)
        }
    }

    private fun startDiscovery() {
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(tag, "Discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "Service found: ${serviceInfo.serviceName}")
                    if (serviceInfo.serviceName.startsWith(App.SERVICE_NAME_PREFIX)) {
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e(tag, "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val deviceName = serviceInfo.serviceName
                                    .removePrefix("${App.SERVICE_NAME_PREFIX}_")
                                val peer = PeerDevice(
                                    name = deviceName,
                                    host = serviceInfo.host.hostAddress,
                                    port = serviceInfo.port
                                )
                                Log.d(tag, "Peer resolved: $peer")

                                synchronized(discoveredPeers) {
                                    val existing = discoveredPeers.indexOfFirst { it.host == peer.host }
                                    if (existing >= 0) {
                                        discoveredPeers[existing] = peer
                                    } else {
                                        discoveredPeers.add(peer)
                                    }
                                }

                                onPeerDiscovered?.invoke(peer)
                            }
                        })
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "Service lost: ${serviceInfo.serviceName}")
                    val deviceName = serviceInfo.serviceName
                        .removePrefix("${App.SERVICE_NAME_PREFIX}_")
                    synchronized(discoveredPeers) {
                        discoveredPeers.removeAll { it.name == deviceName }
                    }
                    onPeerLost?.invoke(deviceName)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(tag, "Discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(tag, "Discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(tag, "Discovery stop failed: $errorCode")
                }
            }

            nsdManager?.discoverServices(App.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start discovery", e)
        }
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop discovery", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                App.CHANNEL_ID,
                "P2P File Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "P2P File Share service notification"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
        }

        return builder
            .setContentTitle("P2P File Share")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(App.NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(tag, "Failed to update notification", e)
        }
    }
}

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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.discovery.UdpDiscoveryBeacon
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

    // WiFi multicast lock - REQUIRED for NSD and UDP discovery to work
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UDP Discovery Beacon (primary discovery method - more reliable than NSD)
    private var udpBeacon: UdpDiscoveryBeacon? = null

    private var discoveredPeers = mutableListOf<PeerDevice>()
    private var onPeerDiscovered: ((PeerDevice) -> Unit)? = null
    private var onPeerLost: ((String) -> Unit)? = null

    // Retry counter for NSD discovery
    private var discoveryRetryCount = 0
    private val MAX_DISCOVERY_RETRIES = 5
    private val DISCOVERY_RETRY_DELAY = 3000L

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
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, P2PService::class.java))
            } catch (e: Exception) {
                Log.e("P2PService", "Failed to stop service", e)
            }
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

        fun getServerPort(): Int {
            return instance?.fileServer?.listeningPort ?: 0
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            instance = this
            isRunning = true
            createNotificationChannel()
            acquireMulticastLock()
        } catch (e: Exception) {
            Log.e(tag, "onCreate failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // CRITICAL: Must call startForeground immediately on Android 8+
            val notification = buildNotification("P2P File Share đang chạy")
            startForegroundSafely(App.NOTIFICATION_ID, notification)

            scope.launch {
                try {
                    startServer()
                    startUdpDiscovery()
                    registerService()
                    startNsdDiscovery()
                } catch (e: Exception) {
                    Log.e(tag, "Error in service startup", e)
                    updateNotification("Lỗi khởi động. Chạm để thử lại.")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "onStartCommand failed", e)
            try {
                val notification = buildNotification("P2P File Share - Khởi động lại")
                startForegroundSafely(App.NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(tag, "Even fallback foreground failed", e2)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            scope.cancel()
            stopUdpDiscovery()
            unregisterService()
            stopNsdDiscovery()
            stopServer()
            releaseMulticastLock()
            mainHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(tag, "onDestroy failed", e)
        } finally {
            isRunning = false
            instance = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartIntent = Intent(this, P2PService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to restart service after task removed", e)
            try {
                val restartTime = System.currentTimeMillis() + 1000
                val alarmIntent = Intent(this, com.p2pfileshare.app.receiver.BootReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    restartTime,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e(tag, "AlarmManager restart also failed", e2)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============ WiFi Multicast Lock ============

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("P2PFileShareLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(tag, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null
            Log.d(tag, "MulticastLock released")
        } catch (e: Exception) {
            Log.e(tag, "Failed to release multicast lock", e)
        }
    }

    // ============ StartForeground safely ============

    private fun startForegroundSafely(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, 0)
            } else {
                startForeground(id, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed (attempt 1)", e)
            try {
                startForeground(id, notification)
            } catch (e2: Exception) {
                Log.e(tag, "startForeground failed (attempt 2)", e2)
                try {
                    val fallbackNotification = buildFallbackNotification()
                    startForeground(id, fallbackNotification)
                } catch (e3: Exception) {
                    Log.e(tag, "All startForeground attempts failed", e3)
                }
            }
        }
    }

    // ============ Server ============

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

    // ============ UDP Discovery (PRIMARY - more reliable than NSD) ============

    private fun startUdpDiscovery() {
        try {
            if (udpBeacon != null) {
                udpBeacon?.stop()
            }

            udpBeacon = UdpDiscoveryBeacon(prefs).apply {
                onPeerDiscovered = { peer ->
                    Log.d(tag, "UDP: Peer discovered: ${peer.name} @ ${peer.host}:${peer.port}")
                    addPeerFromDiscovery(peer)
                }
                onPeerLost = { peerInfo ->
                    Log.d(tag, "UDP: Peer lost: $peerInfo")
                    removePeerFromDiscovery(peerInfo)
                }
            }
            udpBeacon?.start()
            Log.d(tag, "UDP Discovery Beacon started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start UDP discovery", e)
        }
    }

    private fun stopUdpDiscovery() {
        try {
            udpBeacon?.stop()
            udpBeacon = null
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop UDP discovery", e)
        }
    }

    // ============ NSD Registration (keep for compatibility) ============

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
                    Log.d(tag, "NSD: Service registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "NSD: Registration failed: $errorCode")
                    mainHandler.postDelayed({ registerService() }, 5000)
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(tag, "NSD: Service unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "NSD: Unregistration failed: $errorCode")
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
            registrationListener = null
        } catch (e: Exception) {
            Log.e(tag, "Failed to unregister service", e)
        }
    }

    // ============ NSD Discovery (SECONDARY - backup for UDP) ============

    private fun startNsdDiscovery() {
        try {
            stopNsdDiscovery()

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(tag, "NSD: Discovery started for: $serviceType")
                    discoveryRetryCount = 0
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "NSD: Service found: ${serviceInfo.serviceName}")
                    val serviceName = serviceInfo.serviceName ?: ""
                    if (serviceName.startsWith(App.SERVICE_NAME_PREFIX)) {
                        resolveService(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "NSD: Service lost: ${serviceInfo.serviceName}")
                    // Don't remove peer here - UDP timeout handles it
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(tag, "NSD: Discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(tag, "NSD: Discovery start failed: $errorCode, retry=$discoveryRetryCount")
                    if (discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                        discoveryRetryCount++
                        mainHandler.postDelayed({
                            stopNsdDiscovery()
                            startNsdDiscovery()
                        }, DISCOVERY_RETRY_DELAY)
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(tag, "NSD: Discovery stop failed: $errorCode")
                }
            }

            nsdManager?.discoverServices(App.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start NSD discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "NSD: Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                    if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                        mainHandler.postDelayed({ resolveService(serviceInfo) }, 1000)
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        val rawName = serviceInfo.serviceName ?: return
                        val deviceName = rawName
                            .removePrefix("${App.SERVICE_NAME_PREFIX}_")
                            .replace(Regex(" \\(\\d+\\)$"), "")

                        val hostAddress = serviceInfo.host?.hostAddress
                        if (hostAddress == null) {
                            Log.e(tag, "NSD: Resolved peer has null host address")
                            return
                        }

                        val peer = PeerDevice(
                            name = deviceName,
                            host = hostAddress,
                            port = serviceInfo.port
                        )

                        addPeerFromDiscovery(peer)
                    } catch (e: Exception) {
                        Log.e(tag, "NSD: Error processing resolved peer", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "NSD: Error resolving service", e)
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
            discoveryListener = null
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop NSD discovery", e)
        }
    }

    // ============ Unified Peer Management ============
    // Merges peers from both UDP discovery and NSD

    @Synchronized
    private fun addPeerFromDiscovery(peer: PeerDevice) {
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

    @Synchronized
    private fun removePeerFromDiscovery(name: String) {
        synchronized(discoveredPeers) {
            // Remove by both name AND host to handle edge cases
            // Primary: match by name (from UDP timeout which passes name)
            // Also remove any peer with matching host to prevent duplicates
            val toRemove = discoveredPeers.filter { it.name == name || it.host == name }
            for (peer in toRemove) {
                discoveredPeers.remove(peer)
            }
        }
        onPeerLost?.invoke(name)
    }

    // ============ Notifications ============

    private fun createNotificationChannel() {
        try {
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
        } catch (e: Exception) {
            Log.e(tag, "Failed to create notification channel", e)
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

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, App.CHANNEL_ID)
                    .setContentTitle("P2P File Share")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("P2P File Share")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build()
            }
        } catch (e: Exception) {
            Log.e(tag, "buildNotification failed, using fallback", e)
            buildFallbackNotification()
        }
    }

    private fun buildFallbackNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
                .setContentTitle("P2P File Share")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("P2P File Share")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
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

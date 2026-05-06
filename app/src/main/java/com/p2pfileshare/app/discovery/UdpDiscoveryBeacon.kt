package com.p2pfileshare.app.discovery

import android.util.Log
import com.p2pfileshare.app.App
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.util.PreferencesManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * UDP Discovery Beacon - replaces unreliable NSD/mDNS discovery.
 *
 * How it works:
 * 1. Each device broadcasts a UDP packet every 2 seconds on port 9528
 * 2. The packet contains: {v, h, n, p, t, ip} where:
 *    - v = protocol version
 *    - h = HMAC hash (proves this is our app)
 *    - n = device name
 *    - p = HTTP server port
 *    - t = timestamp (for replay protection)
 *    - ip = sender's own IP
 * 3. All devices listen for these broadcasts on port 9528
 * 4. When a valid broadcast is received, the sender is added as a discovered peer
 * 5. If no broadcast received from a peer within 10 seconds, it's considered lost
 *
 * Security:
 * - The HMAC hash is computed from a shared app secret + device name + port + timestamp
 * - Only devices with the same app (and secret) can generate valid hashes
 * - Timestamp prevents replay attacks (reject packets older than 30 seconds)
 */
class UdpDiscoveryBeacon(private val prefs: PreferencesManager) {

    companion object {
        private const val TAG = "UdpDiscovery"
        const val BROADCAST_PORT = 9528
        private const val BROADCAST_INTERVAL_MS = 2000L  // Send beacon every 2s
        private const val PEER_TIMEOUT_MS = 10000L        // Consider peer lost after 10s
        private const val TIMESTAMP_TOLERANCE_MS = 30000L // Reject packets older than 30s
        private const val PROTOCOL_VERSION = 1

        // Shared app secret - only this app knows it
        // This ensures only our app instances can discover each other
        private const val APP_SECRET = "P2PFileShare2024!SecureDiscoveryKey@Z"

        /**
         * Generate HMAC-SHA256 hash for authentication
         */
        private fun generateHash(deviceName: String, port: Int, timestamp: Long): String {
            try {
                val message = "$deviceName:$port:$timestamp"
                val mac = Mac.getInstance("HmacSHA256")
                val secretKey = SecretKeySpec(APP_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
                mac.init(secretKey)
                val hashBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
                return hashBytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate hash", e)
                return ""
            }
        }

        /**
         * Verify if a hash is valid (proves the sender is our app)
         */
        private fun verifyHash(deviceName: String, port: Int, timestamp: Long, hash: String): Boolean {
            val expected = generateHash(deviceName, port, timestamp)
            return expected.isNotEmpty() && expected == hash
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null
    private var timeoutThread: Thread? = null

    // Discovered peers with their last seen timestamp
    private val discoveredPeers = ConcurrentHashMap<String, PeerInfo>()

    // Callbacks
    var onPeerDiscovered: ((PeerDevice) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null  // Passes "host:name" for better dedup

    data class PeerInfo(
        val peer: PeerDevice,
        var lastSeen: Long
    )

    /**
     * Start the discovery beacon (both broadcasting and listening)
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Beacon already running")
            return
        }

        // Start broadcasting
        broadcastThread = Thread({ broadcastLoop() }, "UdpBeacon-Broadcast").apply {
            isDaemon = true
            start()
        }

        // Start listening
        listenThread = Thread({ listenLoop() }, "UdpBeacon-Listen").apply {
            isDaemon = true
            start()
        }

        // Start timeout checker
        timeoutThread = Thread({ timeoutLoop() }, "UdpBeacon-Timeout").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "UDP Discovery Beacon started")
    }

    /**
     * Stop the discovery beacon
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try { broadcastSocket?.close() } catch (_: Exception) {}
        broadcastSocket = null

        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null

        try { broadcastThread?.interrupt() } catch (_: Exception) {}
        broadcastThread = null

        try { listenThread?.interrupt() } catch (_: Exception) {}
        listenThread = null

        try { timeoutThread?.interrupt() } catch (_: Exception) {}
        timeoutThread = null

        discoveredPeers.clear()

        Log.d(TAG, "UDP Discovery Beacon stopped")
    }

    /**
     * Get all currently discovered peers
     */
    fun getDiscoveredPeers(): List<PeerDevice> {
        return discoveredPeers.values.map { it.peer }
    }

    /**
     * Broadcast loop - sends beacon packets every BROADCAST_INTERVAL_MS
     */
    private fun broadcastLoop() {
        try {
            broadcastSocket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create broadcast socket", e)
            return
        }

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val myIp = App.getWifiIpAddress()
                val myName = prefs.serviceName
                val myPort = com.p2pfileshare.app.service.P2PService.getServerPort()
                    .takeIf { it > 0 } ?: prefs.serverPort

                val timestamp = System.currentTimeMillis()
                val hash = generateHash(myName, myPort, timestamp)

                val json = JSONObject().apply {
                    put("v", PROTOCOL_VERSION)
                    put("h", hash)
                    put("n", URLEncoder.encode(myName, "UTF-8"))
                    put("p", myPort)
                    put("t", timestamp)
                    put("ip", myIp)
                }

                val data = json.toString().toByteArray(Charsets.UTF_8)

                // Broadcast to multiple addresses for reliability
                val broadcastAddresses = getBroadcastAddresses()
                for (addr in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(data, data.size, addr, BROADCAST_PORT)
                        broadcastSocket?.send(packet)
                    } catch (_: Exception) {
                        // Some addresses may fail, that's OK
                    }
                }

                Log.d(TAG, "Beacon sent: $myName @ $myIp:$myPort")
            } catch (e: Exception) {
                if (!isRunning.get()) break
                Log.e(TAG, "Broadcast error", e)
            }

            try {
                Thread.sleep(BROADCAST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }

        try { broadcastSocket?.close() } catch (_: Exception) {}
    }

    /**
     * Listen loop - receives beacon packets from other devices
     */
    private fun listenLoop() {
        try {
            listenSocket = DatagramSocket(BROADCAST_PORT).apply {
                reuseAddress = true
                soTimeout = 3000
            }
            Log.d(TAG, "Listening for beacons on port $BROADCAST_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create listen socket on port $BROADCAST_PORT", e)
            return
        }

        val buffer = ByteArray(4096)

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                listenSocket?.receive(packet) ?: break

                val receivedData = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val senderIp = packet.address.hostAddress ?: continue

                processBeacon(receivedData, senderIp)
            } catch (_: java.net.SocketTimeoutException) {
                // Normal timeout, continue
            } catch (e: Exception) {
                if (!isRunning.get()) break
                // Socket might be closed during shutdown
            }
        }

        try { listenSocket?.close() } catch (_: Exception) {}
    }

    /**
     * Process a received beacon packet
     */
    private fun processBeacon(data: String, senderIp: String) {
        try {
            val json = JSONObject(data)
            val version = json.optInt("v", 0)
            if (version != PROTOCOL_VERSION) return

            val hash = json.optString("h", "")
            val encodedName = json.optString("n", "")
            val port = json.optInt("p", 0)
            val timestamp = json.optLong("t", 0)
            val reportedIp = json.optString("ip", "")

            if (hash.isEmpty() || encodedName.isEmpty() || port == 0) return

            val deviceName = URLDecoder.decode(encodedName, "UTF-8")

            // Verify timestamp (reject old packets)
            val now = System.currentTimeMillis()
            if (Math.abs(now - timestamp) > TIMESTAMP_TOLERANCE_MS) {
                Log.d(TAG, "Rejecting old beacon from $deviceName (age=${now - timestamp}ms)")
                return
            }

            // Verify HMAC hash - this is the security check
            // Only our app can generate valid hashes
            if (!verifyHash(deviceName, port, timestamp, hash)) {
                Log.d(TAG, "Rejecting invalid beacon from $deviceName (bad hash)")
                return
            }

            // Skip if this is our own beacon (same IP)
            val myIp = App.getWifiIpAddress()
            if (senderIp == myIp || reportedIp == myIp) return

            // Use the sender's actual IP (from packet) as primary, fall back to reported IP
            val peerIp = if (senderIp != "0.0.0.0" && senderIp != "127.0.0.1") senderIp else reportedIp
            if (peerIp.isEmpty() || peerIp == "Unknown") return

            val peer = PeerDevice(
                name = deviceName,
                host = peerIp,
                port = port
            )

            val wasNew = !discoveredPeers.containsKey(peerIp)
            val existingInfo = discoveredPeers[peerIp]
            val nameChanged = existingInfo != null && existingInfo.peer.name != deviceName
            discoveredPeers[peerIp] = PeerInfo(peer, System.currentTimeMillis())

            if (wasNew) {
                Log.d(TAG, "Peer discovered: $deviceName @ $peerIp:$port")
                onPeerDiscovered?.invoke(peer)
            } else if (nameChanged) {
                // Name changed but same host - notify to update UI
                Log.d(TAG, "Peer updated: $deviceName @ $peerIp:$port")
                onPeerDiscovered?.invoke(peer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing beacon", e)
        }
    }

    /**
     * Timeout loop - removes peers that haven't been seen recently
     */
    private fun timeoutLoop() {
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                break
            }

            try {
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<String>()

                for ((ip, info) in discoveredPeers) {
                    if (now - info.lastSeen > PEER_TIMEOUT_MS) {
                        toRemove.add(ip)
                    }
                }

                for (ip in toRemove) {
                    val info = discoveredPeers.remove(ip)
                    if (info != null) {
                        Log.d(TAG, "Peer lost: ${info.peer.name} @ $ip")
                        // Pass host for reliable dedup, fall back to name
                        onPeerLost?.invoke(ip)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in timeout loop", e)
            }
        }
    }

    /**
     * Get broadcast addresses for the current WiFi network.
     */
    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        try {
            // Add the universal broadcast address
            addresses.add(InetAddress.getByName("255.255.255.255"))

            // Try to determine subnet broadcast address from our IP
            val myIp = App.getWifiIpAddress()
            if (myIp != "Unknown" && myIp.contains(".")) {
                val parts = myIp.split(".")
                if (parts.size == 4) {
                    // 192.168.x.x -> 192.168.x.255
                    val subnetBroadcast = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                    addresses.add(InetAddress.getByName(subnetBroadcast))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast addresses", e)
        }

        return addresses
    }
}

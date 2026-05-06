package com.p2pfileshare.app.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2pfileshare.app.model.ApiResponse
import com.p2pfileshare.app.model.DirectoryInfo
import com.p2pfileshare.app.model.FileItem
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.model.ZipEntryItem
import com.p2pfileshare.app.model.ZipContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiClient {
    private val gson = Gson()
    private val tag = "ApiClient"

    // Cache peer tokens after discovery
    private val peerTokens = mutableMapOf<String, String>()

    /**
     * Get the API token for a peer device.
     * First call to /api/info retrieves and caches the token.
     */
    private suspend fun ensureToken(peer: PeerDevice): String? {
        val key = "${peer.host}:${peer.port}"
        if (peerTokens.containsKey(key)) {
            return peerTokens[key]
        }

        // If peer already has token, use it
        if (peer.token.isNotEmpty()) {
            peerTokens[key] = peer.token
            return peer.token
        }

        // Fetch token from peer's /api/info endpoint
        return try {
            val json = httpGet("http://${peer.host}:${peer.port}/api/info")
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                val token = dataMap["token"] as? String ?: ""
                if (token.isNotEmpty()) {
                    peerTokens[key] = token
                    token
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(tag, "Failed to get token from peer", e)
            null
        }
    }

    suspend fun getInfo(peer: PeerDevice): ApiResponse? = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("http://${peer.host}:${peer.port}/api/info")
            // Also cache token from info response
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                val token = dataMap["token"] as? String ?: ""
                if (token.isNotEmpty()) {
                    peerTokens["${peer.host}:${peer.port}"] = token
                }
            }
            response
        } catch (e: Exception) {
            Log.e(tag, "getInfo failed", e)
            null
        }
    }

    suspend fun listFiles(peer: PeerDevice, path: String = "/"): DirectoryInfo? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val json = httpGet("http://${peer.host}:${peer.port}/api/list?path=$encodedPath", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataJson = gson.toJson(response.data)
                gson.fromJson(dataJson, DirectoryInfo::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(tag, "listFiles failed", e)
            null
        }
    }

    suspend fun downloadFile(peer: PeerDevice, path: String, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val url = URL("http://${peer.host}:${peer.port}/api/download?path=$encodedPath&token=$token")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            if (token != null) {
                conn.setRequestProperty("x-p2p-token", token)
            }

            val sourceFile = File(path)
            val fileName = sourceFile.name
            val disposition = conn.getHeaderField("Content-Disposition")
            val serverFileName = if (disposition != null && disposition.contains("filename=")) {
                disposition.substringAfter("filename=\"").substringBefore("\"")
            } else fileName

            val destFile = File(destDir, serverFileName)
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        output.write(buffer, 0, len)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "downloadFile failed", e)
            false
        }
    }

    suspend fun uploadFile(peer: PeerDevice, file: File, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val boundary = "----P2PBoundary${System.currentTimeMillis()}"
            val url = URL("http://${peer.host}:${peer.port}/api/upload?path=${URLEncoder.encode(targetPath, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token != null) {
                conn.setRequestProperty("x-p2p-token", token)
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 120000

            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (fis.read(buffer).also { len = it } > 0) {
                        dos.write(buffer, 0, len)
                    }
                }
                dos.writeBytes("\r\n--$boundary--\r\n")
                dos.flush()
            }

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            Log.e(tag, "uploadFile failed", e)
            false
        }
    }

    suspend fun createFolder(peer: PeerDevice, path: String, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/create-folder?path=${URLEncoder.encode(path, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "createFolder failed", e)
            false
        }
    }

    suspend fun createFile(peer: PeerDevice, path: String, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/create-file?path=${URLEncoder.encode(path, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "createFile failed", e)
            false
        }
    }

    suspend fun deleteFile(peer: PeerDevice, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val url = URL("http://${peer.host}:${peer.port}/api/delete?path=${URLEncoder.encode(path, "UTF-8")}&token=$token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            if (token != null) {
                conn.setRequestProperty("x-p2p-token", token)
            }

            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "deleteFile failed", e)
            false
        }
    }

    suspend fun renameFile(peer: PeerDevice, oldPath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/rename?oldPath=${URLEncoder.encode(oldPath, "UTF-8")}&newName=${URLEncoder.encode(newName, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "renameFile failed", e)
            false
        }
    }

    suspend fun editFile(peer: PeerDevice, path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val boundary = "----P2PBoundary${System.currentTimeMillis()}"
            val url = URL("http://${peer.host}:${peer.port}/api/edit")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token != null) {
                conn.setRequestProperty("x-p2p-token", token)
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"path\"\r\n\r\n")
                dos.writeBytes(URLEncoder.encode(path, "UTF-8"))
                dos.writeBytes("\r\n--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"content\"\r\n\r\n")
                dos.writeBytes(URLEncoder.encode(content, "UTF-8"))
                dos.writeBytes("\r\n--$boundary--\r\n")
                dos.flush()
            }

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            Log.e(tag, "editFile failed", e)
            false
        }
    }

    suspend fun getFileContent(peer: PeerDevice, path: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            httpGet("http://${peer.host}:${peer.port}/api/download?path=$encodedPath", token)
        } catch (e: Exception) {
            Log.e(tag, "getFileContent failed", e)
            null
        }
    }

    suspend fun copyFile(peer: PeerDevice, sourcePath: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/copy?src=${URLEncoder.encode(sourcePath, "UTF-8")}&dest=${URLEncoder.encode(destPath, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "copyFile failed", e)
            false
        }
    }

    suspend fun moveFile(peer: PeerDevice, sourcePath: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/move?src=${URLEncoder.encode(sourcePath, "UTF-8")}&destDir=${URLEncoder.encode(destPath, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "moveFile failed", e)
            false
        }
    }

    suspend fun getStorageInfo(peer: PeerDevice): String? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/storage-info", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                val total = (dataMap["totalBytes"] as? Number)?.toLong() ?: 0L
                val free = (dataMap["freeBytes"] as? Number)?.toLong() ?: 0L
                val used = total - free
                "${formatStorageSize(used)} / ${formatStorageSize(total)}"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "getStorageInfo failed", e)
            null
        }
    }

    // ========================
    // ZIP VIEWER API
    // ========================

    suspend fun listZipEntries(peer: PeerDevice, path: String): List<ZipEntryItem>? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val json = httpGet("http://${peer.host}:${peer.port}/api/zip-list?path=$encodedPath", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataJson = gson.toJson(response.data)
                val type = object : TypeToken<List<ZipEntryItem>>() {}.type
                gson.fromJson(dataJson, type)
            } else null
        } catch (e: Exception) {
            Log.e(tag, "listZipEntries failed", e)
            null
        }
    }

    suspend fun getZipEntryContent(peer: PeerDevice, path: String, entry: String): ZipContent? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val encodedEntry = URLEncoder.encode(entry, "UTF-8")
            val json = httpGet("http://${peer.host}:${peer.port}/api/zip-entry?path=$encodedPath&entry=$encodedEntry", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataJson = gson.toJson(response.data)
                gson.fromJson(dataJson, ZipContent::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getZipEntryContent failed", e)
            null
        }
    }


    // ========================
    // REMOTE CONTROL API
    // ========================

    data class ScreenInfo(
        val width: Int = 720,
        val height: Int = 1280,
        val captureActive: Boolean = false,
        val gestureActive: Boolean = false
    )

    suspend fun getScreenInfo(peer: PeerDevice): ScreenInfo? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/screen-info", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                ScreenInfo(
                    width = (dataMap["width"] as? Number)?.toInt() ?: 720,
                    height = (dataMap["height"] as? Number)?.toInt() ?: 1280,
                    captureActive = dataMap["captureActive"] as? Boolean ?: false,
                    gestureActive = dataMap["gestureActive"] as? Boolean ?: false
                )
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getScreenInfo failed", e)
            null
        }
    }

    suspend fun getScreenshot(peer: PeerDevice): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val url = URL("http://${peer.host}:${peer.port}/api/screenshot?token=$token")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            if (token != null) {
                conn.setRequestProperty("x-p2p-token", token)
            }
            val bitmap = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
            conn.disconnect()
            bitmap
        } catch (e: Exception) {
            Log.e(tag, "getScreenshot failed", e)
            null
        }
    }

    suspend fun sendTouch(peer: PeerDevice, x: Float, y: Float, action: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/touch?x=$x&y=$y&action=${URLEncoder.encode(action, "UTF-8")}", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "sendTouch failed", e)
            false
        }
    }

    suspend fun sendSwipe(peer: PeerDevice, startX: Float, startY: Float, dx: Float, dy: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/swipe?startX=$startX&startY=$startY&dx=$dx&dy=$dy", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "sendSwipe failed", e)
            false
        }
    }

    // ========================
    // APP MANAGEMENT API
    // ========================

    data class AppInfo(
        val name: String,
        val packageName: String,
        val isSystemApp: Boolean,
        val enabled: Boolean
    )

    suspend fun listApps(peer: PeerDevice, type: String = "user"): List<AppInfo>? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/apps?type=$type", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                val appsList = dataMap["apps"] as? List<Map<String, Any>> ?: return@withContext null
                appsList.map { app ->
                    AppInfo(
                        name = app["name"] as? String ?: "",
                        packageName = app["packageName"] as? String ?: "",
                        isSystemApp = app["isSystemApp"] as? Boolean ?: false,
                        enabled = app["enabled"] as? Boolean ?: true
                    )
                }
            } else null
        } catch (e: Exception) {
            Log.e(tag, "listApps failed", e)
            null
        }
    }

    suspend fun uninstallApp(peer: PeerDevice, packageName: String, silent: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/uninstall-app?package=${URLEncoder.encode(packageName, "UTF-8")}&silent=$silent", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "uninstallApp failed", e)
            false
        }
    }

    // ========================
    // WIFI CONTROL API
    // ========================

    data class WifiStatus(
        val wifiEnabled: Boolean,
        val connected: Boolean,
        val ipAddress: String,
        val ssid: String = "",
        val linkSpeed: Int = 0,
        val signalStrength: Int = 0,
        val totalRxBytes: Long = 0,
        val totalTxBytes: Long = 0
    )

    data class AppWifiRestriction(
        val name: String,
        val packageName: String,
        val limitKbps: Int,
        val status: String
    )

    suspend fun getWifiStatus(peer: PeerDevice): WifiStatus? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/wifi-status", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                WifiStatus(
                    wifiEnabled = dataMap["wifiEnabled"] as? Boolean ?: false,
                    connected = dataMap["connected"] as? Boolean ?: false,
                    ipAddress = dataMap["ipAddress"] as? String ?: "",
                    ssid = dataMap["ssid"] as? String ?: "",
                    linkSpeed = (dataMap["linkSpeed"] as? Number)?.toInt() ?: 0,
                    signalStrength = (dataMap["signalStrength"] as? Number)?.toInt() ?: 0,
                    totalRxBytes = (dataMap["totalRxBytes"] as? Number)?.toLong() ?: 0,
                    totalTxBytes = (dataMap["totalTxBytes"] as? Number)?.toLong() ?: 0
                )
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getWifiStatus failed", e)
            null
        }
    }

    suspend fun controlWifi(peer: PeerDevice, action: String, packageName: String? = null, limitKbps: Int? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            var url = "http://${peer.host}:${peer.port}/api/wifi-control?action=${URLEncoder.encode(action, "UTF-8")}"
            if (packageName != null) url += "&package=${URLEncoder.encode(packageName, "UTF-8")}"
            if (limitKbps != null) url += "&limitKbps=$limitKbps"
            val json = httpGet(url, token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "controlWifi failed", e)
            false
        }
    }

    suspend fun getWifiRestrictions(peer: PeerDevice): List<AppWifiRestriction>? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/wifi-control?action=list_restricted", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                val restrictionsList = dataMap["restrictions"] as? List<Map<String, Any>> ?: return@withContext null
                restrictionsList.map { r ->
                    AppWifiRestriction(
                        name = r["name"] as? String ?: "",
                        packageName = r["package"] as? String ?: "",
                        limitKbps = (r["limitKbps"] as? Number)?.toInt() ?: 0,
                        status = r["status"] as? String ?: "unknown"
                    )
                }
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getWifiRestrictions failed", e)
            null
        }
    }

    suspend fun getNetworkStats(peer: PeerDevice): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val token = ensureToken(peer)
            val json = httpGet("http://${peer.host}:${peer.port}/api/network-stats", token)
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                gson.fromJson(gson.toJson(response.data), Map::class.java) as Map<String, Any>
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getNetworkStats failed", e)
            null
        }
    }

    // ========================
    // HELPER METHODS
    // ========================

    /**
     * Get the cached API token for a peer device.
     * Used by FilePreviewActivity for direct URL downloads.
     */
    fun getPeerToken(peer: PeerDevice): String {
        val key = "${peer.host}:${peer.port}"
        return peerTokens[key] ?: peer.token
    }

    private fun formatStorageSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    private fun httpGet(urlStr: String, token: String? = null): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        if (token != null) {
            conn.setRequestProperty("x-p2p-token", token)
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        conn.disconnect()
        return response.toString()
    }
}

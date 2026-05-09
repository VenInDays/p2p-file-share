package com.p2pfileshare.app.server

import com.google.gson.Gson
import com.p2pfileshare.app.App
import com.p2pfileshare.app.model.ApiResponse
import com.p2pfileshare.app.model.DirectoryInfo
import com.p2pfileshare.app.model.FileItem
import com.p2pfileshare.app.model.ZipEntryItem
import com.p2pfileshare.app.security.SecurityManager
import com.p2pfileshare.app.util.PreferencesManager
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

class FileServer(port: Int, private val prefs: PreferencesManager) : NanoHTTPD(port) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val params = session.parms
        val clientIp = session.remoteIpAddress ?: "unknown"

        // === SECURITY LAYER 1: Rate Limiting ===
        if (!SecurityManager.checkRateLimit(clientIp)) {
            return jsonError("Rate limit exceeded. Try again later.", Response.Status.TOO_MANY_REQUESTS)
        }

        // === SECURITY LAYER 2: API Token Authentication ===
        // All /api/ endpoints require valid token except /api/info (for discovery)
        val authToken = session.headers?.get("x-p2p-token") ?: params["token"]
        if (uri != "/api/info" && !SecurityManager.validateApiToken(authToken)) {
            return jsonError("Unauthorized. Invalid or missing API token.", Response.Status.UNAUTHORIZED)
        }

        // === SECURITY LAYER 3: Lock Check ===
        if (prefs.isLocked && uri != "/api/info") {
            return jsonError("This device is locked", Response.Status.FORBIDDEN)
        }

        return try {
            when {
                uri == "/api/info" -> handleInfo()
                uri == "/api/list" -> handleList(params)
                uri == "/api/download" -> handleDownload(params)
                uri == "/api/upload" && method == Method.POST -> handleUpload(session)
                uri == "/api/create-folder" -> handleCreateFolder(params)
                uri == "/api/create-file" -> handleCreateFile(params)
                uri == "/api/delete" && method == Method.DELETE -> handleDelete(params)
                uri == "/api/rename" && method == Method.POST -> handleRename(params)
                uri == "/api/edit" && method == Method.POST -> handleEdit(session)
                uri == "/api/file-info" -> handleFileInfo(params)
                uri == "/api/copy" && method == Method.POST -> handleCopy(params)
                uri == "/api/move" && method == Method.POST -> handleMove(params)
                uri == "/api/storage-info" -> handleStorageInfo()
                uri == "/api/search" -> handleSearch(params)
                uri == "/api/zip-list" -> handleZipList(params)
                uri == "/api/zip-entry" -> handleZipEntry(params)
                // App management
                uri == "/api/apps" -> handleListApps(params)
                uri == "/api/uninstall-app" -> handleUninstallApp(params)
                // WiFi control
                uri == "/api/wifi-status" -> handleWifiStatus()
                uri == "/api/wifi-control" -> handleWifiControl(params)
                uri == "/api/network-stats" -> handleNetworkStats()
                else -> jsonError("Unknown endpoint: $uri", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            jsonError("Server error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleInfo(): Response {
        val data = mapOf(
            "name" to prefs.serviceName,
            "port" to listeningPort,
            "locked" to prefs.isLocked,
            "version" to "1.9.1",
            "token" to SecurityManager.getApiToken() // Share token so paired devices can authenticate
        )
        return jsonSuccess("OK", data)
    }

    private fun handleList(params: Map<String, String>): Response {
        var path = params["path"] ?: "/"
        path = URLDecoder.decode(path, "UTF-8")
        val dir = if (path == "/") File(App.getStorageRoot()) else File(path)

        if (!dir.exists() || !dir.isDirectory) {
            return jsonError("Directory not found: $path")
        }

        val files = dir.listFiles()?.map { file ->
            FileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                lastModified = file.lastModified(),
                mimeType = getMimeType(file),
                readable = file.canRead(),
                writable = file.canWrite()
            )
        }?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        val parent = dir.parent
        val dirInfo = DirectoryInfo(
            path = dir.absolutePath,
            files = files,
            parent = parent
        )
        return jsonSuccess("OK", dirInfo)
    }

    private fun handleDownload(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        path = URLDecoder.decode(path, "UTF-8")
        val file = File(path)

        if (!file.exists()) {
            return jsonError("File not found")
        }

        if (file.isDirectory) {
            // Zip the directory
            val zipFile = File(file.parent, "${file.name}.zip")
            zipDirectory(file, zipFile)
            val fis = FileInputStream(zipFile)
            val response = newFixedLengthResponse(Response.Status.OK, "application/zip", fis, zipFile.length())
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}.zip\"")
            zipFile.deleteOnExit()
            return response
        }

        val fis = FileInputStream(file)
        val mimeType = getMimeType(file)
        val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return response
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        val params = HashMap<String, String>()
        session.parseBody(files)

        var targetPath = params["path"] ?: session.parms["path"] ?: "/"
        targetPath = URLDecoder.decode(targetPath, "UTF-8")
        val targetDir = if (targetPath == "/") File(App.getStorageRoot()) else File(targetPath)

        if (!targetDir.exists() || !targetDir.isDirectory) {
            return jsonError("Target directory not found")
        }

        val uploadedFiles = mutableListOf<String>()
        for ((key, tempFilePath) in files) {
            val tempFile = File(tempFilePath)
            val originalName = session.parms[key] ?: key
            val targetFile = File(targetDir, originalName)

            if (targetFile.exists()) {
                targetFile.delete()
            }

            tempFile.copyTo(targetFile)
            uploadedFiles.add(targetFile.absolutePath)
        }

        return jsonSuccess("Uploaded ${uploadedFiles.size} file(s)", uploadedFiles)
    }

    private fun handleCreateFolder(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        var name = params["name"] ?: return jsonError("Name is required")
        path = URLDecoder.decode(path, "UTF-8")
        name = URLDecoder.decode(name, "UTF-8")

        val parent = if (path == "/") File(App.getStorageRoot()) else File(path)
        val newDir = File(parent, name)

        if (newDir.exists()) {
            return jsonError("Already exists: ${newDir.absolutePath}")
        }

        val created = newDir.mkdirs()
        return if (created) {
            jsonSuccess("Folder created", newDir.absolutePath)
        } else {
            jsonError("Failed to create folder")
        }
    }

    private fun handleCreateFile(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        var name = params["name"] ?: return jsonError("Name is required")
        path = URLDecoder.decode(path, "UTF-8")
        name = URLDecoder.decode(name, "UTF-8")

        val parent = if (path == "/") File(App.getStorageRoot()) else File(path)
        val newFile = File(parent, name)

        if (newFile.exists()) {
            return jsonError("Already exists: ${newFile.absolutePath}")
        }

        val created = newFile.createNewFile()
        return if (created) {
            jsonSuccess("File created", newFile.absolutePath)
        } else {
            jsonError("Failed to create file")
        }
    }

    private fun handleDelete(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        path = URLDecoder.decode(path, "UTF-8")
        val file = File(path)

        if (!file.exists()) {
            return jsonError("Not found: $path")
        }

        val deleted = deleteRecursively(file)
        return if (deleted) {
            jsonSuccess("Deleted", path)
        } else {
            jsonError("Failed to delete")
        }
    }

    private fun handleRename(params: Map<String, String>): Response {
        var oldPath = params["oldPath"] ?: return jsonError("Old path is required")
        var newName = params["newName"] ?: return jsonError("New name is required")
        oldPath = URLDecoder.decode(oldPath, "UTF-8")
        newName = URLDecoder.decode(newName, "UTF-8")

        val file = File(oldPath)
        if (!file.exists()) {
            return jsonError("Not found: $oldPath")
        }

        val newFile = File(file.parent, newName)
        val renamed = file.renameTo(newFile)
        return if (renamed) {
            jsonSuccess("Renamed", newFile.absolutePath)
        } else {
            jsonError("Failed to rename")
        }
    }

    private fun handleEdit(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        val params = HashMap<String, String>()
        session.parseBody(files)

        var path = params["path"] ?: session.parms["path"] ?: return jsonError("Path is required")
        path = URLDecoder.decode(path, "UTF-8")
        val content = params["content"] ?: session.parms["content"] ?: return jsonError("Content is required")

        val file = File(path)
        if (!file.exists()) {
            return jsonError("File not found")
        }

        try {
            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
            }
            return jsonSuccess("File saved", path)
        } catch (e: Exception) {
            return jsonError("Failed to save: ${e.message}")
        }
    }

    private fun handleFileInfo(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        path = URLDecoder.decode(path, "UTF-8")
        val file = File(path)

        if (!file.exists()) {
            return jsonError("Not found")
        }

        val info = FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            lastModified = file.lastModified(),
            mimeType = getMimeType(file),
            readable = file.canRead(),
            writable = file.canWrite()
        )
        return jsonSuccess("OK", info)
    }

    // ========================
    // ZIP VIEWER ENDPOINTS
    // ========================

    /**
     * List contents of a ZIP file without extracting.
     * Returns a list of ZipEntryItem with name, size, compressed size, and isDirectory.
     */
    private fun handleZipList(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("Path is required")
        path = URLDecoder.decode(path, "UTF-8")
        val file = File(path)

        if (!file.exists()) {
            return jsonError("ZIP file not found")
        }

        if (!file.name.lowercase().endsWith(".zip")) {
            return jsonError("Not a ZIP file")
        }

        try {
            val entries = mutableListOf<ZipEntryItem>()
            val zipFile = ZipFile(file)

            zipFile.entries().asSequence().forEach { entry ->
                entries.add(
                    ZipEntryItem(
                        name = entry.name,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        isDirectory = entry.isDirectory,
                        lastModified = entry.time
                    )
                )
            }

            zipFile.close()
            return jsonSuccess("OK", entries)
        } catch (e: Exception) {
            return jsonError("Failed to read ZIP: ${e.message}")
        }
    }

    /**
     * Extract and return a single entry from a ZIP file.
     * Used to view individual text files inside a ZIP.
     */
    private fun handleZipEntry(params: Map<String, String>): Response {
        var path = params["path"] ?: return jsonError("ZIP path is required")
        var entryName = params["entry"] ?: return jsonError("Entry name is required")
        path = URLDecoder.decode(path, "UTF-8")
        entryName = URLDecoder.decode(entryName, "UTF-8")

        val file = File(path)
        if (!file.exists()) {
            return jsonError("ZIP file not found")
        }

        try {
            val zipFile = ZipFile(file)
            val entry = zipFile.getEntry(entryName)

            if (entry == null) {
                zipFile.close()
                return jsonError("Entry not found: $entryName")
            }

            if (entry.isDirectory) {
                zipFile.close()
                return jsonError("Cannot read directory entry")
            }

            // Check if it's a text file
            val entryLower = entryName.lowercase()
            val isTextFile = entryLower.endsWith(".txt") || entryLower.endsWith(".log") ||
                    entryLower.endsWith(".md") || entryLower.endsWith(".json") ||
                    entryLower.endsWith(".xml") || entryLower.endsWith(".html") ||
                    entryLower.endsWith(".css") || entryLower.endsWith(".js") ||
                    entryLower.endsWith(".csv") || entryLower.endsWith(".properties") ||
                    entryLower.endsWith(".yaml") || entryLower.endsWith(".yml") ||
                    entryLower.endsWith(".ini") || entryLower.endsWith(".conf") ||
                    entryLower.endsWith(".sh") || entryLower.endsWith(".bat")

            val isImageFile = entryLower.endsWith(".jpg") || entryLower.endsWith(".jpeg") ||
                    entryLower.endsWith(".png") || entryLower.endsWith(".gif") ||
                    entryLower.endsWith(".webp") || entryLower.endsWith(".bmp")

            val inputStream = zipFile.getInputStream(entry)
            val bytes = inputStream.readBytes()
            inputStream.close()
            zipFile.close()

            // Size limit: 2MB for text, 10MB for images
            val maxSize = if (isTextFile) 2 * 1024 * 1024 else 10 * 1024 * 1024
            if (bytes.size > maxSize) {
                return jsonError("File too large to preview: ${bytes.size} bytes")
            }

            if (isTextFile) {
                val content = String(bytes, Charsets.UTF_8)
                val data = mapOf(
                    "name" to entryName,
                    "content" to content,
                    "size" to bytes.size.toLong(),
                    "type" to "text"
                )
                return jsonSuccess("OK", data)
            } else if (isImageFile) {
                // Return as binary with proper MIME type
                val mimeType = when {
                    entryLower.endsWith(".jpg") || entryLower.endsWith(".jpeg") -> "image/jpeg"
                    entryLower.endsWith(".png") -> "image/png"
                    entryLower.endsWith(".gif") -> "image/gif"
                    entryLower.endsWith(".webp") -> "image/webp"
                    else -> "image/bmp"
                }
                val bais = ByteArrayInputStream(bytes)
                val response = newFixedLengthResponse(Response.Status.OK, mimeType, bais, bytes.size.toLong())
                return response
            } else {
                // Return basic info for unsupported types
                val data = mapOf(
                    "name" to entryName,
                    "size" to bytes.size.toLong(),
                    "type" to "binary",
                    "message" to "Preview not supported for this file type"
                )
                return jsonSuccess("OK", data)
            }
        } catch (e: Exception) {
            return jsonError("Failed to read ZIP entry: ${e.message}")
        }
    }

    // ========================
    // COPY / MOVE / STORAGE / SEARCH
    // ========================

    private fun handleCopy(params: Map<String, String>): Response {
        var src = params["src"] ?: return jsonError("Source path is required")
        var dest = params["dest"] ?: return jsonError("Destination directory is required")
        src = URLDecoder.decode(src, "UTF-8")
        dest = URLDecoder.decode(dest, "UTF-8")

        val srcFile = File(src)
        if (!srcFile.exists()) {
            return jsonError("Source not found: $src")
        }

        val destDir = File(dest)
        if (!destDir.exists() || !destDir.isDirectory) {
            return jsonError("Destination directory not found: $dest")
        }

        val targetFile = resolveUniqueDestination(destDir, srcFile.name)

        val success = if (srcFile.isDirectory) {
            copyDirectoryRecursive(srcFile, targetFile)
        } else {
            try {
                srcFile.copyTo(targetFile)
                true
            } catch (e: Exception) {
                false
            }
        }

        return if (success) {
            jsonSuccess("Copied", targetFile.absolutePath)
        } else {
            jsonError("Failed to copy")
        }
    }

    private fun handleMove(params: Map<String, String>): Response {
        var src = params["src"] ?: return jsonError("Source path is required")
        var destDir = params["destDir"] ?: return jsonError("Destination directory is required")
        src = URLDecoder.decode(src, "UTF-8")
        destDir = URLDecoder.decode(destDir, "UTF-8")

        val srcFile = File(src)
        if (!srcFile.exists()) {
            return jsonError("Source not found: $src")
        }

        val destDirFile = File(destDir)
        if (!destDirFile.exists() || !destDirFile.isDirectory) {
            return jsonError("Destination directory not found: $destDir")
        }

        val targetFile = resolveUniqueDestination(destDirFile, srcFile.name)

        // Try renameTo first (works if same filesystem)
        val renamed = srcFile.renameTo(targetFile)
        if (renamed) {
            return jsonSuccess("Moved", targetFile.absolutePath)
        }

        // Fallback: copy + delete
        val copySuccess = if (srcFile.isDirectory) {
            copyDirectoryRecursive(srcFile, targetFile)
        } else {
            try {
                srcFile.copyTo(targetFile)
                true
            } catch (e: Exception) {
                false
            }
        }

        if (!copySuccess) {
            return jsonError("Failed to move: copy failed")
        }

        val deleted = deleteRecursively(srcFile)
        return if (deleted) {
            jsonSuccess("Moved", targetFile.absolutePath)
        } else {
            jsonError("Moved (copy succeeded) but failed to delete source")
        }
    }

    private fun handleStorageInfo(): Response {
        val storageRoot = File(App.getStorageRoot())
        val totalSpace = storageRoot.totalSpace
        val freeSpace = storageRoot.freeSpace
        val usableSpace = storageRoot.usableSpace
        val usedSpace = totalSpace - usableSpace

        val data = mapOf(
            "totalBytes" to totalSpace,
            "usedBytes" to usedSpace,
            "freeBytes" to usableSpace
        )
        return jsonSuccess("OK", data)
    }

    private fun handleSearch(params: Map<String, String>): Response {
        var path = params["path"] ?: "/"
        val query = params["query"] ?: return jsonError("Query is required")
        path = URLDecoder.decode(path, "UTF-8")

        val rootDir = if (path == "/") File(App.getStorageRoot()) else File(path)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return jsonError("Directory not found: $path")
        }

        val results = mutableListOf<FileItem>()
        searchRecursive(rootDir, query.lowercase(), results, maxDepth = 3, currentDepth = 0)
        return jsonSuccess("OK", results)
    }

    // ========================
    // APP MANAGEMENT ENDPOINTS
    // ========================

    /**
     * List installed apps on this device.
     * Query params: type = "all" | "user" | "system" (default: "user")
     */
    private fun handleListApps(params: Map<String, String>): Response {
        try {
            val type = params["type"] ?: "user"
            val ctx = App.instance ?: return jsonError("App context not available")
            val pm = ctx.packageManager

            @Suppress("DEPRECATION")
            val apps = pm.getInstalledApplications(0)

            val filteredApps = apps.filter { appInfo ->
                when (type) {
                    "user" -> (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                    "system" -> (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    else -> true  // "all"
                }
            }.map { appInfo ->
                val name = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    appInfo.packageName
                }
                mapOf(
                    "name" to name,
                    "packageName" to appInfo.packageName,
                    "isSystemApp" to ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0),
                    "enabled" to appInfo.enabled,
                    "uid" to appInfo.uid
                )
            }.sortedBy { it["name"] as String }

            return jsonSuccess("OK", mapOf("apps" to filteredApps, "count" to filteredApps.size))
        } catch (e: Exception) {
            return jsonError("Failed to list apps: ${e.message}")
        }
    }

    /**
     * Uninstall an app by package name.
     * Uses Intent-based uninstall which shows system confirmation dialog.
     * For silent uninstall (requires system app or root), uses pm command.
     * Post params: package = "com.example.app", silent = "false"
     */
    private fun handleUninstallApp(params: Map<String, String>): Response {
        try {
            val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name is required"), "UTF-8")
            val silent = params["silent"]?.toBoolean() ?: false
            val disableOnly = params["disable"]?.toBoolean() ?: true  // Default: disable instead of uninstall (more reliable)

            // Prevent uninstalling our own app
            if (packageName == "com.p2pfileshare.app") {
                return jsonError("Cannot uninstall P2P File Share")
            }

            val ctx = App.instance ?: return jsonError("App context not available")

            // Check if package exists
            try {
                ctx.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                return jsonError("App not found: $packageName")
            }

            // Method 1: If we are Device Owner, use DevicePolicyManager to uninstall silently
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(ctx, com.p2pfileshare.app.admin.P2PDeviceAdminReceiver::class.java)
            val isDeviceOwner = dpm?.isDeviceOwnerApp(ctx.packageName) == true

            if (isDeviceOwner && silent) {
                // Device Owner can silently uninstall apps
                try {
                    dpm.uninstallPackageAdmin(adminComponent, packageName)
                    return jsonSuccess("App uninstalled silently (Device Owner)", mapOf("package" to packageName, "method" to "device_owner"))
                } catch (e: Exception) {
                    // Fall through to other methods
                    android.util.Log.w("FileServer", "Device Owner uninstall failed, trying alternatives", e)
                }
            }

            // Method 2: Disable the app (works without root - app disappears from launcher)
            // This is the most reliable method for non-root, non-device-owner
            if (disableOnly && !silent) {
                try {
                    // Check if already disabled
                    val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                    if (!appInfo.enabled) {
                        return jsonSuccess("App is already disabled", mapOf("package" to packageName, "method" to "already_disabled"))
                    }

                    // Disable the app - it will disappear from launcher and cannot run
                    ctx.packageManager.setApplicationEnabledSetting(
                        packageName,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                        0
                    )
                    return jsonSuccess("App disabled (hidden from launcher, cannot run)", mapOf(
                        "package" to packageName,
                        "method" to "disabled",
                        "note" to "App is hidden and cannot run. Use enable-app to restore."
                    ))
                } catch (e: Exception) {
                    android.util.Log.w("FileServer", "Disable app failed, trying uninstall dialog", e)
                    // Fall through to uninstall dialog
                }
            }

            // Method 3: Try silent uninstall via pm command (requires root or system app)
            if (silent) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", packageName))
                    val exitCode = process.waitFor()
                    val output = process.inputStream.bufferedReader().readText()
                    if (exitCode == 0 && output.contains("Success")) {
                        return jsonSuccess("App uninstalled silently", mapOf("package" to packageName, "method" to "pm_uninstall"))
                    } else {
                        // pm uninstall failed, try disabling instead
                        try {
                            ctx.packageManager.setApplicationEnabledSetting(
                                packageName,
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                                0
                            )
                            return jsonSuccess("App disabled (silent uninstall not available)", mapOf(
                                "package" to packageName, "method" to "disabled",
                                "note" to "Silent uninstall requires root. App has been disabled instead."
                            ))
                        } catch (e2: Exception) {
                            return jsonError("Silent uninstall failed and disable also failed: $output")
                        }
                    }
                } catch (e: Exception) {
                    return jsonError("Silent uninstall failed: ${e.message}. Try non-silent mode.")
                }
            }

            // Method 4: Standard uninstall with system confirmation dialog
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return jsonSuccess("Uninstall dialog opened for $packageName", mapOf(
                    "package" to packageName, "method" to "dialog",
                    "note" to "User must confirm uninstall on the remote device"
                ))
            } catch (e: Exception) {
                return jsonError("Failed to open uninstall dialog: ${e.message}")
            }
        } catch (e: Exception) {
            return jsonError("Failed to uninstall app: ${e.message}")
        }
    }

    // ========================
    // WIFI CONTROL ENDPOINTS
    // ========================

    /**
     * Get WiFi status including connection info, bandwidth usage, and connected clients.
     */
    private fun handleWifiStatus(): Response {
        try {
            val ctx = App.instance ?: return jsonError("App context not available")
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager

            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager?.connectionInfo
            val isConnected = wifiInfo != null && wifiInfo.networkId != -1

            val data = mutableMapOf<String, Any>()
            @Suppress("DEPRECATION")
            data["wifiEnabled"] = (wifiManager?.isWifiEnabled ?: false)
            data["connected"] = isConnected
            data["ipAddress"] = App.getWifiIpAddress()

            if (isConnected && wifiInfo != null) {
                @Suppress("DEPRECATION")
                val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: "Unknown"
                data["ssid"] = ssid
                data["linkSpeed"] = wifiInfo.linkSpeed  // Mbps
                @Suppress("DEPRECATION")
                data["frequency"] = if (android.os.Build.VERSION.SDK_INT >= 21) {
                    wifiInfo.frequency  // MHz
                } else 0
                data["signalStrength"] = wifiInfo.rssi  // dBm
            }

            // Network stats
            try {
                val stats = getNetworkStats(ctx)
                data.putAll(stats)
            } catch (e: Exception) {
                // Network stats not available
            }

            return jsonSuccess("OK", data)
        } catch (e: Exception) {
            return jsonError("Failed to get WiFi status: ${e.message}")
        }
    }

    /**
     * Control WiFi settings.
     * Post params:
     *   action = "enable" | "disable" | "restrict_app" | "unrestrict_app" | "set_bandwidth_limit"
     *   package = "com.example.app" (for restrict/unrestrict)
     *   limitKbps = "1000" (for bandwidth limit in kbps)
     */
    private fun handleWifiControl(params: Map<String, String>): Response {
        try {
            val action = URLDecoder.decode(params["action"] ?: return jsonError("Action is required"), "UTF-8")
            val ctx = App.instance ?: return jsonError("App context not available")

            when (action) {
                "enable" -> {
                    // Android 10+ (API 29) cannot programmatically enable WiFi
                    // Must use Settings.Panel or WiFi panel intent
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        // Open WiFi settings panel on the remote device
                        try {
                            val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(panelIntent)
                            return jsonSuccess("WiFi settings panel opened on remote device (Android 10+)", mapOf("action" to "panel_opened", "note" to "User must enable WiFi manually"))
                        } catch (e: Exception) {
                            // Fallback: open WiFi settings
                            try {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(settingsIntent)
                                return jsonSuccess("WiFi settings opened on remote device", mapOf("action" to "settings_opened"))
                            } catch (e2: Exception) {
                                return jsonError("Cannot open WiFi settings: ${e2.message}")
                            }
                        }
                    } else {
                        // Pre-Android 10: can programmatically toggle WiFi
                        try {
                            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            @Suppress("DEPRECATION")
                            wifiManager?.isWifiEnabled = true
                            return jsonSuccess("WiFi enabled", mapOf("action" to "enabled"))
                        } catch (e: Exception) {
                            return jsonError("Failed to enable WiFi: ${e.message}")
                        }
                    }
                }
                "disable" -> {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try {
                            val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(panelIntent)
                            return jsonSuccess("WiFi settings panel opened on remote device (Android 10+)", mapOf("action" to "panel_opened", "note" to "User must disable WiFi manually"))
                        } catch (e: Exception) {
                            try {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(settingsIntent)
                                return jsonSuccess("WiFi settings opened on remote device", mapOf("action" to "settings_opened"))
                            } catch (e2: Exception) {
                                return jsonError("Cannot open WiFi settings: ${e2.message}")
                            }
                        }
                    } else {
                        try {
                            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            @Suppress("DEPRECATION")
                            wifiManager?.isWifiEnabled = false
                            return jsonSuccess("WiFi disabled", mapOf("action" to "disabled"))
                        } catch (e: Exception) {
                            return jsonError("Failed to disable WiFi: ${e.message}")
                        }
                    }
                }
                "restrict_app" -> {
                    val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name required for restrict_app"), "UTF-8")
                    val limitKbps = params["limitKbps"]?.toIntOrNull() ?: 0  // 0 = block completely

                    // Prevent restricting our own app
                    if (packageName == "com.p2pfileshare.app") {
                        return jsonError("Cannot restrict P2P File Share")
                    }

                    // Save restriction preference
                    saveAppWifiRestriction(packageName, limitKbps)

                    // Method 1: If Device Owner, use DevicePolicyManager for real network restriction
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(ctx, com.p2pfileshare.app.admin.P2PDeviceAdminReceiver::class.java)
                    val isDeviceOwner = dpm?.isDeviceOwnerApp(ctx.packageName) == true

                    if (isDeviceOwner) {
                        try {
                            if (limitKbps == 0) {
                                // Block app completely - set network policy via DevicePolicyManager
                                // For Device Owner, we can use setPermittedInputMethods, setAccountManagement, etc.
                                // For network restriction specifically, use setApplicationRestrictions
                                // But the most effective: disable the app's network access
                                // Using Android's built-in network policy
                                val uid = getAppUid(ctx, packageName)
                                if (uid > 0) {
                                    // Try using cmd netpolicy (works for Device Owner)
                                    try {
                                        val process = Runtime.getRuntime().exec(
                                            arrayOf("cmd", "netpolicy", "setUidPolicy", uid.toString(), "1")
                                        )
                                        process.waitFor()
                                        return jsonSuccess("App $packageName blocked from network (Device Owner)", mapOf(
                                            "package" to packageName, "action" to "blocked", "method" to "device_owner_netpolicy"
                                        ))
                                    } catch (e: Exception) {
                                        // netpolicy not available, try disable approach
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "Device Owner restriction failed", e)
                        }
                    }

                    // Method 2: Try shell commands (works on some devices with privileged access)
                    if (limitKbps == 0) {
                        // Block app from WiFi completely using cmd netpolicy
                        var shellSuccess = false
                        try {
                            val uid = getAppUid(ctx, packageName)
                            if (uid > 0) {
                                val process = Runtime.getRuntime().exec(
                                    arrayOf("cmd", "netpolicy", "setUidPolicy", uid.toString(), "1")
                                )
                                val exitCode = process.waitFor()
                                val output = process.inputStream.bufferedReader().readText()
                                if (exitCode == 0) {
                                    shellSuccess = true
                                }
                            }
                        } catch (e: Exception) {
                            // Shell command not available
                        }

                        // Also try: disable the app entirely (most reliable non-root method)
                        try {
                            ctx.packageManager.setApplicationEnabledSetting(
                                packageName,
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                                0
                            )
                            return jsonSuccess("App $packageName disabled (cannot use network or run)", mapOf(
                                "package" to packageName,
                                "action" to "disabled",
                                "method" to if (shellSuccess) "netpolicy+disabled" else "disabled",
                                "note" to "App is disabled and cannot run. This effectively blocks all network access."
                            ))
                        } catch (e: Exception) {
                            if (shellSuccess) {
                                return jsonSuccess("App $packageName blocked from network", mapOf(
                                    "package" to packageName, "action" to "blocked", "method" to "netpolicy"
                                ))
                            }
                            return jsonError("Cannot restrict app: no sufficient permissions. Set Device Owner for real network restriction.")
                        }
                    } else {
                        // Bandwidth limiting - only possible with root or Device Owner
                        var bandwidthSet = false
                        try {
                            val uid = getAppUid(ctx, packageName)
                            setupBandwidthLimit(uid, limitKbps)
                            bandwidthSet = true
                        } catch (e: Exception) {
                            // iptables not available
                        }

                        if (bandwidthSet) {
                            return jsonSuccess("Bandwidth limit set for $packageName: ${limitKbps}kbps", mapOf(
                                "package" to packageName, "limitKbps" to limitKbps, "method" to "iptables"
                            ))
                        } else {
                            // Cannot set bandwidth limit, save preference
                            return jsonSuccess("Bandwidth limit saved for $packageName: ${limitKbps}kbps (requires root/Device Owner to enforce)", mapOf(
                                "package" to packageName, "limitKbps" to limitKbps, "method" to "preference_only",
                                "note" to "Set Device Owner via ADB to enforce bandwidth limits"
                            ))
                        }
                    }
                }
                "unrestrict_app" -> {
                    val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name required"), "UTF-8")
                    var anySuccess = false

                    // Remove netpolicy restriction
                    try {
                        val uid = getAppUid(ctx, packageName)
                        if (uid > 0) {
                            val process = Runtime.getRuntime().exec(
                                arrayOf("cmd", "netpolicy", "setUidPolicy", uid.toString(), "0")
                            )
                            process.waitFor()
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        // Shell not available
                    }

                    // Re-enable the app (if it was disabled as a restriction)
                    try {
                        ctx.packageManager.setApplicationEnabledSetting(
                            packageName,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            0
                        )
                        anySuccess = true
                    } catch (e: Exception) {
                        // Cannot re-enable
                    }

                    // Remove bandwidth limits (try)
                    try {
                        val uid = getAppUid(ctx, packageName)
                        if (uid > 0) {
                            // Remove iptables rules
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "iptables -D FORWARD -m owner --uid-owner $uid -j DROP")).waitFor()
                        }
                    } catch (e: Exception) {
                        // Not root
                    }

                    removeAppWifiRestriction(packageName)

                    return if (anySuccess) {
                        jsonSuccess("App $packageName WiFi restriction removed", mapOf("package" to packageName, "action" to "unrestricted"))
                    } else {
                        jsonSuccess("App $packageName restriction removed from preferences", mapOf("package" to packageName, "note" to "Restriction was saved as preference only"))
                    }
                }
                "list_restricted" -> {
                    val restrictions = getWifiRestrictions()
                    return jsonSuccess("OK", mapOf("restrictions" to restrictions))
                }
                "enable_app" -> {
                    // Re-enable an app that was disabled by restrict_app
                    val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name required"), "UTF-8")
                    try {
                        ctx.packageManager.setApplicationEnabledSetting(
                            packageName,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            0
                        )
                        removeAppWifiRestriction(packageName)
                        return jsonSuccess("App $packageName re-enabled", mapOf("package" to packageName, "action" to "enabled"))
                    } catch (e: Exception) {
                        return jsonError("Cannot re-enable app: ${e.message}")
                    }
                }
                else -> return jsonError("Unknown action: $action. Valid: enable, disable, restrict_app, unrestrict_app, enable_app, list_restricted")
            }
        } catch (e: Exception) {
            return jsonError("WiFi control failed: ${e.message}")
        }
    }

    /**
     * Get network statistics including per-app data usage.
     */
    private fun handleNetworkStats(): Response {
        try {
            val ctx = App.instance ?: return jsonError("App context not available")

            val data = mutableMapOf<String, Any>()
            data.putAll(getNetworkStats(ctx))

            // Per-app network usage
            try {
                val appUsage = getPerAppNetworkUsage(ctx)
                data["appUsage"] = appUsage
            } catch (e: Exception) {
                data["appUsageError"] = "Per-app stats not available: ${e.message}"
            }

            // WiFi restrictions
            data["restrictions"] = getWifiRestrictions()

            return jsonSuccess("OK", data)
        } catch (e: Exception) {
            return jsonError("Failed to get network stats: ${e.message}")
        }
    }

    // ========================
    // WIFI CONTROL HELPERS
    // ========================

    private fun getNetworkStats(ctx: Context): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        try {
            // Get total bytes since device boot
            val totalRx = android.net.TrafficStats.getTotalRxBytes()
            val totalTx = android.net.TrafficStats.getTotalTxBytes()
            val mobileRx = android.net.TrafficStats.getMobileRxBytes()
            val mobileTx = android.net.TrafficStats.getMobileTxBytes()

            stats["totalRxBytes"] = totalRx
            stats["totalTxBytes"] = totalTx
            stats["mobileRxBytes"] = mobileRx
            stats["mobileTxBytes"] = mobileTx
            stats["wifiRxBytes"] = totalRx - mobileRx
            stats["wifiTxBytes"] = totalTx - mobileTx
        } catch (e: Exception) {
            stats["error"] = "TrafficStats not available"
        }
        return stats
    }

    private fun getPerAppNetworkUsage(ctx: Context): List<Map<String, Any>> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)

        return apps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { appInfo ->
                val uid = appInfo.uid
                val rxBytes = android.net.TrafficStats.getUidRxBytes(uid)
                val txBytes = android.net.TrafficStats.getUidTxBytes(uid)
                if (rxBytes > 0 || txBytes > 0) {
                    val name = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        appInfo.packageName
                    }
                    mapOf(
                        "name" to name,
                        "package" to appInfo.packageName,
                        "uid" to uid,
                        "rxBytes" to rxBytes,
                        "txBytes" to txBytes,
                        "totalBytes" to rxBytes + txBytes
                    )
                } else null
            }.sortedByDescending { it["totalBytes"] as Long }
            .take(50)  // Top 50 apps by usage
    }

    private fun getAppUid(ctx: Context, packageName: String): Int {
        return try {
            ctx.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (e: Exception) {
            -1
        }
    }

    private fun setupBandwidthLimit(uid: Int, limitKbps: Int) {
        // Use iptables for bandwidth control
        // This requires root, but we try anyway
        try {
            // Limit download speed
            val downloadCmd = "iptables -A FORWARD -m owner --uid-owner $uid -m limit --limit ${limitKbps}kb/s -j ACCEPT"
            val dropCmd = "iptables -A FORWARD -m owner --uid-owner $uid -j DROP"
            Runtime.getRuntime().exec(arrayOf("su", "-c", downloadCmd)).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", dropCmd)).waitFor()
        } catch (e: Exception) {
            // Not root, save preference for later
        }
    }

    private fun saveAppWifiRestriction(packageName: String, limitKbps: Int) {
        try {
            val ctx = App.instance ?: return
            val prefs = ctx.getSharedPreferences("wifi_restrictions", Context.MODE_PRIVATE)
            prefs.edit().putInt("limit_$packageName", limitKbps).apply()
        } catch (_: Exception) {}
    }

    private fun removeAppWifiRestriction(packageName: String) {
        try {
            val ctx = App.instance ?: return
            val prefs = ctx.getSharedPreferences("wifi_restrictions", Context.MODE_PRIVATE)
            prefs.edit().remove("limit_$packageName").apply()
        } catch (_: Exception) {}
    }

    private fun getWifiRestrictions(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        try {
            val ctx = App.instance ?: return result
            val prefs = ctx.getSharedPreferences("wifi_restrictions", Context.MODE_PRIVATE)
            val pm = ctx.packageManager
            for ((key, value) in prefs.all) {
                if (key.startsWith("limit_")) {
                    val packageName = key.removePrefix("limit_")
                    val limitKbps = value as Int
                    val name = try {
                        pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    result.add(mapOf(
                        "name" to name,
                        "package" to packageName,
                        "limitKbps" to limitKbps,
                        "status" to if (limitKbps == 0) "blocked" else "limited"
                    ))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    // ========================
    // HELPER METHODS
    // ========================

    private fun resolveUniqueDestination(destDir: File, name: String): File {
        var target = File(destDir, name)
        if (!target.exists()) return target

        val baseName: String
        val extension: String
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0 && !File(name).isDirectory) {
            baseName = name.substring(0, dotIndex)
            extension = name.substring(dotIndex)
        } else {
            baseName = name
            extension = ""
        }

        var counter = 1
        while (target.exists()) {
            target = File(destDir, "$baseName ($counter)$extension")
            counter++
        }
        return target
    }

    private fun copyDirectoryRecursive(src: File, dest: File): Boolean {
        if (!dest.exists()) {
            val created = dest.mkdirs()
            if (!created) return false
        }

        val children = src.listFiles() ?: return true
        for (child in children) {
            val destChild = File(dest, child.name)
            val success = if (child.isDirectory) {
                copyDirectoryRecursive(child, destChild)
            } else {
                try {
                    child.copyTo(destChild)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!success) return false
        }
        return true
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileItem>, maxDepth: Int, currentDepth: Int) {
        if (currentDepth > maxDepth) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            if (child.name.lowercase().contains(query)) {
                results.add(
                    FileItem(
                        name = child.name,
                        path = child.absolutePath,
                        isDirectory = child.isDirectory,
                        size = if (child.isFile) child.length() else 0,
                        lastModified = child.lastModified(),
                        mimeType = getMimeType(child),
                        readable = child.canRead(),
                        writable = child.canWrite()
                    )
                )
            }
            if (child.isDirectory) {
                searchRecursive(child, query, results, maxDepth, currentDepth + 1)
            }
        }
    }

    private fun jsonSuccess(message: String, data: Any?): Response {
        val json = gson.toJson(ApiResponse(true, message, data))
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun jsonError(message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
        val json = gson.toJson(ApiResponse(false, message, null))
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
    }

    private fun getMimeType(file: File): String {
        val name = file.name.lowercase()
        return when {
            file.isDirectory -> "directory"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".xml") -> "application/xml"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".bmp") -> "image/bmp"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".pdf") -> "application/pdf"
            name.endsWith(".zip") -> "application/zip"
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zipRecursive(sourceDir, sourceDir.name, zos)
        }
    }

    private fun zipRecursive(file: File, basePath: String, zos: java.util.zip.ZipOutputStream) {
        if (file.isDirectory) {
            val entries = file.listFiles() ?: return
            if (entries.isEmpty()) {
                zos.putNextEntry(java.util.zip.ZipEntry("$basePath/"))
                zos.closeEntry()
            } else {
                for (entry in entries) {
                    zipRecursive(entry, "$basePath/${entry.name}", zos)
                }
            }
        } else {
            zos.putNextEntry(java.util.zip.ZipEntry(basePath))
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(4096)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    zos.write(buffer, 0, len)
                }
            }
            zos.closeEntry()
        }
    }
}

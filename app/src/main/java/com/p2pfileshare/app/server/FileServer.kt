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
                uri == "/api/kill-app" -> handleKillApp(params)
                uri == "/api/open-app" -> handleOpenApp(params)
                // Audio control
                uri == "/api/play-audio" -> handlePlayAudio(params)
                uri == "/api/audio-status" -> handleAudioStatus()
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
            "version" to "1.10.0",
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
     * For Device Owner: uses pm uninstall (silent, no dialog) or pm disable
     * For non-Device Owner: opens system uninstall dialog
     * Params: package = "com.example.app", silent = "false", disable = "true"
     */
    private fun handleUninstallApp(params: Map<String, String>): Response {
        try {
            val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name is required"), "UTF-8")
            val silent = params["silent"]?.toBoolean() ?: false
            val disableOnly = params["disable"]?.toBoolean() ?: true

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

            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val isDeviceOwner = dpm?.isDeviceOwnerApp(ctx.packageName) == true

            // === Device Owner: can silently uninstall/disable ===
            if (isDeviceOwner) {
                if (disableOnly) {
                    // Disable the app (preferred - can be re-enabled later)
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("pm", "disable", packageName))
                        val exitCode = process.waitFor()
                        val output = process.inputStream.bufferedReader().readText()
                        if (exitCode == 0 || output.contains("disabled") || output.contains("Success")) {
                            return jsonSuccess("App $packageName đã vô hiệu hóa (Device Owner)", mapOf(
                                "package" to packageName, "method" to "pm_disable",
                                "note" to "App bị vô hiệu hóa, ẩn khỏi launcher và không thể chạy"
                            ))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FileServer", "pm disable failed", e)
                    }

                    // Fallback: setApplicationEnabledSetting
                    try {
                        ctx.packageManager.setApplicationEnabledSetting(
                            packageName,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                            0
                        )
                        val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                        if (!appInfo.enabled) {
                            return jsonSuccess("App $packageName đã vô hiệu hóa (Device Owner)", mapOf(
                                "package" to packageName, "method" to "set_enabled_disabled"
                            ))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FileServer", "setApplicationEnabledSetting failed", e)
                    }
                }

                // Actually uninstall the app (permanent)
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", packageName))
                    val exitCode = process.waitFor()
                    val output = process.inputStream.bufferedReader().readText()
                    if (exitCode == 0 || output.contains("Success")) {
                        return jsonSuccess("App $packageName đã gỡ cài đặt (Device Owner)", mapOf(
                            "package" to packageName, "method" to "pm_uninstall"
                        ))
                    }
                    // pm uninstall may fail for system apps
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    android.util.Log.w("FileServer", "pm uninstall output: $output, error: $errorOutput")
                } catch (e: Exception) {
                    android.util.Log.w("FileServer", "pm uninstall failed", e)
                }

                // If uninstall failed, try disabling instead
                if (!disableOnly) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("pm", "disable", packageName))
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            return jsonSuccess("App $packageName không thể gỡ (app hệ thống), đã vô hiệu hóa", mapOf(
                                "package" to packageName, "method" to "pm_disable_fallback",
                                "note" to "App hệ thống không thể gỡ, đã vô hiệu hóa thay thế"
                            ))
                        }
                    } catch (e: Exception) {
                        // pm disable also failed
                    }
                }
            }

            // === Non-Device Owner: try best available method ===
            if (disableOnly) {
                var actuallyDisabled = false
                try {
                    ctx.packageManager.setApplicationEnabledSetting(
                        packageName,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                        0
                    )
                    // Verify it actually took effect
                    val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                    actuallyDisabled = !appInfo.enabled
                } catch (e: Exception) {
                    // Cannot disable
                }

                if (actuallyDisabled) {
                    return jsonSuccess("App $packageName đã vô hiệu hóa", mapOf(
                        "package" to packageName, "method" to "set_enabled_disabled"
                    ))
                }
                // Fall through to uninstall dialog
            }

            // Standard uninstall with system confirmation dialog
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return jsonSuccess("Đã mở hộp thoại gỡ cài đặt cho $packageName", mapOf(
                    "package" to packageName, "method" to "dialog",
                    "note" to "Cần xác nhận gỡ cài đặt trên thiết bị remote"
                ))
            } catch (e: Exception) {
                return jsonError("Không thể gỡ cài đặt app: ${e.message}")
            }
        } catch (e: Exception) {
            return jsonError("Failed to uninstall app: ${e.message}")
        }
    }

    // ========================
    // APP KILL / OPEN / AUDIO ENDPOINTS
    // ========================

    /**
     * Force-stop (kill) an app by package name.
     * Uses 'am force-stop' which works for Device Owner and system apps.
     * Params: package = "com.example.app"
     */
    private fun handleKillApp(params: Map<String, String>): Response {
        try {
            val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name is required"), "UTF-8")

            if (packageName == "com.p2pfileshare.app") {
                return jsonError("Không thể tắt P2P File Share")
            }

            val ctx = App.instance ?: return jsonError("App context not available")

            // Verify package exists
            try {
                ctx.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                return jsonError("App không tồn tại: $packageName")
            }

            // Method 1: am force-stop (works for Device Owner, may work for others too)
            try {
                val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    return jsonSuccess("Đã tắt app $packageName", mapOf(
                        "package" to packageName, "action" to "force_stopped", "method" to "am_force_stop"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.w("FileServer", "am force-stop failed", e)
            }

            // Method 2: kill background processes via ActivityManager
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.killBackgroundProcesses(packageName)
                return jsonSuccess("Đã tắt tiến trình nền của $packageName", mapOf(
                    "package" to packageName, "action" to "killed_background", "method" to "kill_background_processes"
                ))
            } catch (e: Exception) {
                return jsonError("Không thể tắt app $packageName: ${e.message}")
            }
        } catch (e: Exception) {
            return jsonError("Failed to kill app: ${e.message}")
        }
    }

    /**
     * Open (launch) an app by package name.
     * Uses PackageManager to find the launch intent and starts it.
     * Params: package = "com.example.app"
     */
    private fun handleOpenApp(params: Map<String, String>): Response {
        try {
            val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name is required"), "UTF-8")
            val ctx = App.instance ?: return jsonError("App context not available")

            // Verify package exists
            try {
                ctx.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                return jsonError("App không tồn tại: $packageName")
            }

            // Check if app is enabled
            try {
                val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                if (!appInfo.enabled) {
                    return jsonError("App $packageName đang bị vô hiệu hóa. Cần kích hoạt lại trước khi mở.")
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Method 1: Use PackageManager.getLaunchIntentForPackage (standard way)
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                try {
                    ctx.startActivity(launchIntent)
                    val appName = try {
                        ctx.packageManager.getApplicationInfo(packageName, 0)
                        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(packageName, 0)).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    return jsonSuccess("Đã mở app $appName", mapOf(
                        "package" to packageName, "action" to "launched", "method" to "launch_intent"
                    ))
                } catch (e: Exception) {
                    android.util.Log.w("FileServer", "startActivity failed for launch intent", e)
                }
            }

            // Method 2: Try using 'am start' with the package's main activity
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "am", "start", "-n", "$packageName/${packageName}.MainActivity",
                    "--activity-new-task"
                ))
                val exitCode = process.waitFor()
                val output = process.inputStream.bufferedReader().readText()
                if (exitCode == 0 || output.contains("Starting")) {
                    return jsonSuccess("Đã mở app $packageName", mapOf(
                        "package" to packageName, "action" to "launched", "method" to "am_start"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.w("FileServer", "am start failed", e)
            }

            // Method 3: Try monkey command to launch the app
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"
                ))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    return jsonSuccess("Đã mở app $packageName", mapOf(
                        "package" to packageName, "action" to "launched", "method" to "monkey"
                    ))
                }
            } catch (e: Exception) {
                // monkey command not available
            }

            return jsonError("Không thể mở app $packageName - không tìm thấy activity khởi chạy")
        } catch (e: Exception) {
            return jsonError("Failed to open app: ${e.message}")
        }
    }

    /**
     * Play an audio file on the remote device.
     * Uses Android's MediaPlayer via an intent or starts playback in a foreground service.
     * Params: path = "/storage/emulated/0/Music/song.mp3", action = "play" | "stop" | "volume"
     *         volume = "50" (0-100, for volume action)
     */
    private fun handlePlayAudio(params: Map<String, String>): Response {
        try {
            val action = URLDecoder.decode(params["action"] ?: "play", "UTF-8")
            val ctx = App.instance ?: return jsonError("App context not available")

            when (action) {
                "play" -> {
                    var path = URLDecoder.decode(params["path"] ?: return jsonError("Đường dẫn file âm thanh là bắt buộc"), "UTF-8")
                    val file = File(path)

                    if (!file.exists()) {
                        return jsonError("File không tồn tại: $path")
                    }

                    // Check if it's an audio file
                    val name = file.name.lowercase()
                    val isAudio = name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") ||
                            name.endsWith(".flac") || name.endsWith(".aac") || name.endsWith(".m4a") ||
                            name.endsWith(".wma") || name.endsWith(".opus") || name.endsWith(".mid") ||
                            name.endsWith(".midi") || name.endsWith(".amr")
                    if (!isAudio) {
                        return jsonError("File không phải định dạng âm thanh được hỗ trợ: ${file.extension}")
                    }

                    // Stop any current playback first
                    try {
                        AudioPlayerManager.stop()
                    } catch (_: Exception) {}

                    // Method 1: Use our built-in AudioPlayerManager (plays in-app, no external app needed)
                    try {
                        AudioPlayerManager.play(ctx, path)
                        return jsonSuccess("Đang phát: ${file.name}", mapOf(
                            "action" to "playing", "file" to file.name, "path" to path, "method" to "audio_player_manager"
                        ))
                    } catch (e: Exception) {
                        android.util.Log.w("FileServer", "AudioPlayerManager play failed", e)
                    }

                    // Method 2: Use Intent with ACTION_VIEW (opens external audio player)
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx,
                            "${ctx.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, getMimeType(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        return jsonSuccess("Đã mở file âm thanh: ${file.name}", mapOf(
                            "action" to "playing", "file" to file.name, "path" to path, "method" to "intent_view"
                        ))
                    } catch (e: Exception) {
                        android.util.Log.w("FileServer", "Intent ACTION_VIEW failed", e)
                    }

                    // Method 3: Use am start with media player
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf(
                            "am", "start", "-a", "android.intent.action.VIEW",
                            "-d", "file://$path", "-t", getMimeType(file)
                        ))
                        process.waitFor()
                        return jsonSuccess("Đang phát âm thanh", mapOf(
                            "action" to "playing", "file" to file.name, "method" to "am_start"
                        ))
                    } catch (e: Exception) {
                        return jsonError("Không thể phát âm thanh: ${e.message}")
                    }
                }
                "stop" -> {
                    try {
                        AudioPlayerManager.stop()
                        return jsonSuccess("Đã dừng phát âm thanh", mapOf("action" to "stopped"))
                    } catch (e: Exception) {
                        return jsonError("Không thể dừng âm thanh: ${e.message}")
                    }
                }
                "volume" -> {
                    val volume = params["volume"]?.toIntOrNull() ?: return jsonError("Cần nhập mức âm lượng (0-100)")
                    if (volume < 0 || volume > 100) {
                        return jsonError("Mức âm lượng phải từ 0 đến 100")
                    }
                    try {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        if (am != null) {
                            val maxVolume = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val targetVolume = (maxVolume * volume / 100.0).toInt()
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, 0)
                            return jsonSuccess("Âm lượng đã thay đổi: $volume%", mapOf(
                                "action" to "volume_set", "volume" to volume, "targetStream" to targetVolume
                            ))
                        }
                        return jsonError("Không thể điều chỉnh âm lượng")
                    } catch (e: Exception) {
                        return jsonError("Lỗi điều chỉnh âm lượng: ${e.message}")
                    }
                }
                else -> return jsonError("Action không hợp lệ: $action. Hợp lệ: play, stop, volume")
            }
        } catch (e: Exception) {
            return jsonError("Audio control failed: ${e.message}")
        }
    }

    /**
     * Get current audio playback status.
     */
    private fun handleAudioStatus(): Response {
        try {
            val ctx = App.instance ?: return jsonError("App context not available")
            val data = mutableMapOf<String, Any>()

            data["isPlaying"] = AudioPlayerManager.isPlaying()
            data["currentFile"] = AudioPlayerManager.getCurrentFile()

            // Get volume info
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null) {
                    val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    data["volume"] = if (maxVol > 0) (currentVol * 100 / maxVol) else 0
                    data["volumeMax"] = maxVol
                    data["volumeCurrent"] = currentVol
                }
            } catch (e: Exception) {
                data["volumeError"] = e.message ?: "unknown"
            }

            return jsonSuccess("OK", data)
        } catch (e: Exception) {
            return jsonError("Failed to get audio status: ${e.message}")
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
     * Params:
     *   action = "enable" | "disable" | "restrict_app" | "unrestrict_app" | "enable_app" | "list_restricted"
     *   package = "com.example.app" (for restrict/unrestrict)
     *   limitKbps = "1000" (for bandwidth limit in kbps)
     */
    private fun handleWifiControl(params: Map<String, String>): Response {
        try {
            val action = URLDecoder.decode(params["action"] ?: return jsonError("Action is required"), "UTF-8")
            val ctx = App.instance ?: return jsonError("App context not available")

            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(ctx, com.p2pfileshare.app.admin.P2PDeviceAdminReceiver::class.java)
            val isDeviceOwner = dpm?.isDeviceOwnerApp(ctx.packageName) == true

            when (action) {
                "enable" -> {
                    // Method 1: If Device Owner, use 'svc wifi enable' (works on ALL Android versions)
                    if (isDeviceOwner) {
                        try {
                            val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "enable"))
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                return jsonSuccess("WiFi đã bật (Device Owner)", mapOf("action" to "enabled", "method" to "svc_wifi"))
                            }
                            // svc failed, try alternative
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "svc wifi enable failed", e)
                        }
                        // Fallback: try settings put global
                        try {
                            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "wifi_on", "1")).waitFor()
                            // Also trigger wifi change via connectivity service
                            Runtime.getRuntime().exec(arrayOf("svc", "wifi", "enable")).waitFor()
                            return jsonSuccess("WiFi đã bật (Device Owner)", mapOf("action" to "enabled", "method" to "settings_global"))
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "settings put global wifi_on failed", e)
                        }
                    }

                    // Method 2: Pre-Android 10 - can programmatically toggle WiFi
                    if (android.os.Build.VERSION.SDK_INT < 29) {
                        try {
                            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            @Suppress("DEPRECATION")
                            wifiManager?.isWifiEnabled = true
                            return jsonSuccess("WiFi đã bật", mapOf("action" to "enabled", "method" to "wifimanager"))
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "WifiManager enable failed", e)
                        }
                    }

                    // Method 3: Android 10+ without Device Owner - open settings panel
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try {
                            val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(panelIntent)
                            return jsonSuccess("Đã mở bảng cài đặt WiFi (Android 10+ cần bật thủ công)", mapOf("action" to "panel_opened", "note" to "Cần bật WiFi thủ công trên thiết bị remote"))
                        } catch (e: Exception) {
                            try {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(settingsIntent)
                                return jsonSuccess("Đã mở cài đặt WiFi", mapOf("action" to "settings_opened"))
                            } catch (e2: Exception) {
                                return jsonError("Không thể mở cài đặt WiFi: ${e2.message}")
                            }
                        }
                    }

                    return jsonError("Không thể bật WiFi - cần quyền Device Owner hoặc Android < 10")
                }
                "disable" -> {
                    // Method 1: If Device Owner, use 'svc wifi disable' (works on ALL Android versions)
                    if (isDeviceOwner) {
                        try {
                            val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "disable"))
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                return jsonSuccess("WiFi đã tắt (Device Owner)", mapOf("action" to "disabled", "method" to "svc_wifi"))
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "svc wifi disable failed", e)
                        }
                        // Fallback
                        try {
                            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "wifi_on", "0")).waitFor()
                            Runtime.getRuntime().exec(arrayOf("svc", "wifi", "disable")).waitFor()
                            return jsonSuccess("WiFi đã tắt (Device Owner)", mapOf("action" to "disabled", "method" to "settings_global"))
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "settings put global wifi_on failed", e)
                        }
                    }

                    // Method 2: Pre-Android 10
                    if (android.os.Build.VERSION.SDK_INT < 29) {
                        try {
                            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            @Suppress("DEPRECATION")
                            wifiManager?.isWifiEnabled = false
                            return jsonSuccess("WiFi đã tắt", mapOf("action" to "disabled", "method" to "wifimanager"))
                        } catch (e: Exception) {
                            android.util.Log.w("FileServer", "WifiManager disable failed", e)
                        }
                    }

                    // Method 3: Android 10+ without Device Owner
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try {
                            val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(panelIntent)
                            return jsonSuccess("Đã mở bảng cài đặt WiFi (Android 10+ cần tắt thủ công)", mapOf("action" to "panel_opened", "note" to "Cần tắt WiFi thủ công trên thiết bị remote"))
                        } catch (e: Exception) {
                            try {
                                val settingsIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(settingsIntent)
                                return jsonSuccess("Đã mở cài đặt WiFi", mapOf("action" to "settings_opened"))
                            } catch (e2: Exception) {
                                return jsonError("Không thể mở cài đặt WiFi: ${e2.message}")
                            }
                        }
                    }

                    return jsonError("Không thể tắt WiFi - cần quyền Device Owner hoặc Android < 10")
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

                    if (isDeviceOwner) {
                        // === Device Owner: can ACTUALLY restrict apps ===
                        if (limitKbps == 0) {
                            // Block app completely from network - use pm disable (most reliable for Device Owner)
                            try {
                                val process = Runtime.getRuntime().exec(arrayOf("pm", "disable", packageName))
                                val exitCode = process.waitFor()
                                val output = process.inputStream.bufferedReader().readText()
                                val errorOutput = process.errorStream.bufferedReader().readText()
                                if (exitCode == 0 || output.contains("Success") || output.contains("disabled")) {
                                    return jsonSuccess("App $packageName đã bị vô hiệu hóa (chặn mạng)", mapOf(
                                        "package" to packageName, "action" to "blocked",
                                        "method" to "pm_disable",
                                        "note" to "App bị vô hiệu hóa hoàn toàn, không thể chạy hay dùng mạng"
                                    ))
                                }
                                // pm disable failed, try netpolicy
                            } catch (e: Exception) {
                                android.util.Log.w("FileServer", "pm disable failed", e)
                            }

                            // Fallback: try cmd netpolicy to block network data
                            try {
                                val uid = getAppUid(ctx, packageName)
                                if (uid > 0) {
                                    // POLICY_REJECT_METERED = 1, reject all data
                                    val process = Runtime.getRuntime().exec(
                                        arrayOf("cmd", "netpolicy", "setUidPolicy", uid.toString(), "1")
                                    )
                                    val exitCode = process.waitFor()
                                    if (exitCode == 0) {
                                        return jsonSuccess("App $packageName đã chặn dữ liệu mạng (Device Owner)", mapOf(
                                            "package" to packageName, "action" to "blocked", "method" to "netpolicy"
                                        ))
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("FileServer", "netpolicy failed", e)
                            }

                            // Last resort: setApplicationEnabledSetting (works for Device Owner)
                            try {
                                ctx.packageManager.setApplicationEnabledSetting(
                                    packageName,
                                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                                    0
                                )
                                // Verify the setting actually took effect
                                val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                                if (!appInfo.enabled) {
                                    return jsonSuccess("App $packageName đã vô hiệu hóa (chặn mạng)", mapOf(
                                        "package" to packageName, "action" to "blocked",
                                        "method" to "set_enabled_disabled",
                                        "note" to "App bị vô hiệu hóa, không thể chạy hay dùng mạng"
                                    ))
                                } else {
                                    return jsonError("Không thể vô hiệu hóa app $packageName - quyền không đủ")
                                }
                            } catch (e: Exception) {
                                return jsonError("Không thể giới hạn app $packageName: ${e.message}")
                            }
                        } else {
                            // Bandwidth limiting with Device Owner - try iptables via shell
                            try {
                                val uid = getAppUid(ctx, packageName)
                                if (uid > 0) {
                                    setupBandwidthLimit(uid, limitKbps)
                                    return jsonSuccess("Giới hạn băng thông cho $packageName: ${limitKbps}kbps", mapOf(
                                        "package" to packageName, "limitKbps" to limitKbps, "method" to "iptables"
                                    ))
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("FileServer", "iptables bandwidth limit failed", e)
                            }
                            // If iptables not available, save preference only
                            return jsonSuccess("Đã lưu giới hạn băng thông cho $packageName: ${limitKbps}kbps (chỉ lưu, cần root để áp dụng)", mapOf(
                                "package" to packageName, "limitKbps" to limitKbps, "method" to "preference_only"
                            ))
                        }
                    } else {
                        // === NOT Device Owner: cannot reliably restrict other apps ===
                        if (limitKbps == 0) {
                            // Try to disable app - will fail silently for non-Device-Owner
                            var actuallyDisabled = false
                            try {
                                ctx.packageManager.setApplicationEnabledSetting(
                                    packageName,
                                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                                    0
                                )
                                // Verify it actually took effect
                                val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                                actuallyDisabled = !appInfo.enabled
                            } catch (e: Exception) {
                                // Cannot disable
                            }

                            if (actuallyDisabled) {
                                return jsonSuccess("App $packageName đã vô hiệu hóa", mapOf(
                                    "package" to packageName, "action" to "blocked", "method" to "set_enabled_disabled"
                                ))
                            }

                            // Cannot actually restrict - be honest about it
                            return jsonError("Không thể giới hạn WiFi cho $packageName - cần thiết lập Device Owner qua ADB:\nadb shell dpm set-device-owner com.p2pfileshare.app/.admin.P2PDeviceAdminReceiver", Response.Status.FORBIDDEN)
                        } else {
                            return jsonError("Không thể giới hạn băng thông - cần thiết lập Device Owner qua ADB", Response.Status.FORBIDDEN)
                        }
                    }
                }
                "unrestrict_app" -> {
                    val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name required"), "UTF-8")
                    var anySuccess = false
                    val methods = mutableListOf<String>()

                    // If Device Owner: try pm enable first
                    if (isDeviceOwner) {
                        try {
                            val process = Runtime.getRuntime().exec(arrayOf("pm", "enable", packageName))
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                anySuccess = true
                                methods.add("pm_enable")
                            }
                        } catch (e: Exception) {
                            // pm enable failed
                        }
                    }

                    // Remove netpolicy restriction
                    try {
                        val uid = getAppUid(ctx, packageName)
                        if (uid > 0) {
                            val process = Runtime.getRuntime().exec(
                                arrayOf("cmd", "netpolicy", "setUidPolicy", uid.toString(), "0")
                            )
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                anySuccess = true
                                methods.add("netpolicy")
                            }
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
                        // Verify
                        val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                        if (appInfo.enabled) {
                            anySuccess = true
                            methods.add("set_enabled")
                        }
                    } catch (e: Exception) {
                        // Cannot re-enable
                    }

                    // Remove bandwidth limits (try)
                    try {
                        val uid = getAppUid(ctx, packageName)
                        if (uid > 0) {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "iptables -D FORWARD -m owner --uid-owner $uid -j DROP")).waitFor()
                        }
                    } catch (e: Exception) {
                        // Not root
                    }

                    removeAppWifiRestriction(packageName)

                    return if (anySuccess) {
                        jsonSuccess("App $packageName đã bỏ giới hạn WiFi", mapOf("package" to packageName, "action" to "unrestricted", "methods" to methods.joinToString(", ")))
                    } else {
                        jsonSuccess("Đã bỏ giới hạn $packageName khỏi danh sách lưu", mapOf("package" to packageName, "note" to "Giới hạn chỉ được lưu dưới dạng preference"))
                    }
                }
                "list_restricted" -> {
                    val restrictions = getWifiRestrictions()
                    return jsonSuccess("OK", mapOf("restrictions" to restrictions))
                }
                "enable_app" -> {
                    // Re-enable an app that was disabled by restrict_app
                    val packageName = URLDecoder.decode(params["package"] ?: return jsonError("Package name required"), "UTF-8")
                    var success = false

                    // If Device Owner: pm enable
                    if (isDeviceOwner) {
                        try {
                            val process = Runtime.getRuntime().exec(arrayOf("pm", "enable", packageName))
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                success = true
                            }
                        } catch (e: Exception) {
                            // pm enable failed
                        }
                    }

                    // Also try setApplicationEnabledSetting
                    try {
                        ctx.packageManager.setApplicationEnabledSetting(
                            packageName,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            0
                        )
                        val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
                        if (appInfo.enabled) {
                            success = true
                        }
                    } catch (e: Exception) {
                        // Cannot re-enable
                    }

                    removeAppWifiRestriction(packageName)

                    return if (success) {
                        jsonSuccess("App $packageName đã kích hoạt lại", mapOf("package" to packageName, "action" to "enabled"))
                    } else {
                        jsonError("Không thể kích hoạt lại app $packageName: ${if (!isDeviceOwner) "Cần quyền Device Owner" else "Lỗi không xác định"}")
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

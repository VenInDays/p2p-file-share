package com.p2pfileshare.app.server

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2pfileshare.app.App
import com.p2pfileshare.app.model.ApiResponse
import com.p2pfileshare.app.model.AppInfo
import com.p2pfileshare.app.model.DirectoryInfo
import com.p2pfileshare.app.model.FileItem
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

class FileServer(port: Int, private val prefs: PreferencesManager) : NanoHTTPD(port) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var mediaPlayer: MediaPlayer? = null

    override fun serve(session: IHTTPSession): Response {
        // Check if locked
        if (prefs.isLocked) {
            return jsonError("This device is locked", Response.Status.FORBIDDEN)
        }

        val uri = session.uri ?: "/"
        val method = session.method
        val params = session.parms

        return try {
            when {
                // File operations
                uri == "/api/info" -> handleInfo()
                uri == "/api/list" -> handleList(params)
                uri == "/api/download" -> handleDownload(params)
                uri == "/api/upload" && method == Method.POST -> handleUpload(session)
                uri == "/api/create-folder" && method == Method.POST -> handleCreateFolder(params)
                uri == "/api/create-file" && method == Method.POST -> handleCreateFile(params)
                uri == "/api/delete" && method == Method.DELETE -> handleDelete(params)
                uri == "/api/rename" && method == Method.POST -> handleRename(params)
                uri == "/api/edit" && method == Method.POST -> handleEdit(session)
                uri == "/api/file-info" -> handleFileInfo(params)

                // App management
                uri == "/api/apps" && method == Method.GET -> handleAppList(params)
                uri == "/api/apps/force-stop" && method == Method.POST -> handleAppForceStop(params)
                uri == "/api/apps/launch" && method == Method.POST -> handleAppLaunch(params)
                uri == "/api/apps/uninstall" && method == Method.POST -> handleAppUninstall(params)
                uri == "/api/apps/restrict-wifi" && method == Method.POST -> handleAppRestrictWifi(params)

                // WiFi control
                uri == "/api/wifi-control" && method == Method.POST -> handleWifiControl(params)

                // Play audio
                uri == "/api/play-audio" && method == Method.POST -> handlePlayAudio(session)
                uri == "/api/play-audio/stop" && method == Method.POST -> handleStopAudio()

                else -> jsonError("Unknown endpoint: $uri", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            jsonError("Server error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    // ============ File Operations ============

    private fun handleInfo(): Response {
        val data = mapOf(
            "name" to prefs.serviceName,
            "port" to listeningPort,
            "locked" to prefs.isLocked
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

        val targetPath = params["path"] ?: session.parms["path"] ?: App.getStorageRoot()
        val targetDir = File(URLDecoder.decode(targetPath, "UTF-8"))

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

    // ============ App Management ============

    private fun handleAppList(params: Map<String, String>): Response {
        val context = App.instance ?: return jsonError("App context not available")
        val pm = context.packageManager

        // Use getInstalledPackages() with appropriate flags to get ALL apps
        // This is the key fix: use getInstalledPackages instead of queryIntentActivities
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        } catch (e: Exception) {
            // Fallback
            try {
                pm.getInstalledPackages(0)
            } catch (e2: Exception) {
                return jsonError("Failed to list apps: ${e2.message}")
            }
        }

        val filter = params["filter"] ?: "all" // all, user, system
        val apps = mutableListOf<AppInfo>()

        for (pkgInfo in installedPackages) {
            val appInfo = pkgInfo.applicationInfo
            val name = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkgInfo.packageName
            }

            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isEnabled = appInfo.enabled
            val hasLaunchIntent = try {
                pm.getLaunchIntentForPackage(pkgInfo.packageName) != null
            } catch (e: Exception) {
                false
            }

            when (filter) {
                "user" -> if (!isSystemApp) apps.add(AppInfo(name, pkgInfo.packageName, isSystemApp, isEnabled, hasLaunchIntent))
                "system" -> if (isSystemApp) apps.add(AppInfo(name, pkgInfo.packageName, isSystemApp, isEnabled, hasLaunchIntent))
                else -> apps.add(AppInfo(name, pkgInfo.packageName, isSystemApp, isEnabled, hasLaunchIntent))
            }
        }

        // Sort: user apps first, then alphabetically
        apps.sortWith(compareBy<AppInfo> { it.isSystemApp }.thenBy { it.name.lowercase() })

        return jsonSuccess("OK", apps)
    }

    private fun handleAppForceStop(params: Map<String, String>): Response {
        val packageName = params["packageName"] ?: return jsonError("packageName is required")
        val context = App.instance ?: return jsonError("App context not available")

        // Try am force-stop (works with Device Owner or root)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return jsonSuccess("Force stopped: $packageName", null)
            }
        } catch (e: Exception) {
            // Continue to fallback
        }

        // Fallback: killBackgroundProcesses (requires KILL_BACKGROUND_PROCESSES permission)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            return jsonSuccess("Killed background processes: $packageName", null)
        } catch (e: Exception) {
            return jsonError("Failed to force stop: ${e.message}")
        }
    }

    private fun handleAppLaunch(params: Map<String, String>): Response {
        val packageName = params["packageName"] ?: return jsonError("packageName is required")
        val context = App.instance ?: return jsonError("App context not available")
        val pm = context.packageManager

        // Method 1: Try launch intent from PackageManager
        try {
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
                return jsonSuccess("Launched: $packageName", null)
            }
        } catch (e: Exception) {
            // Continue to fallback
        }

        // Method 2: Try am start command
        try {
            // First try to get the main activity
            val process = Runtime.getRuntime().exec(arrayOf("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", packageName))
            val output = process.inputStream.bufferedReader().readText().trim()
            val lines = output.lines()
            if (lines.size >= 2) {
                val component = lines.last()
                Runtime.getRuntime().exec(arrayOf("am", "start", "-n", component)).waitFor()
                return jsonSuccess("Launched via am start: $packageName", null)
            }
        } catch (e: Exception) {
            // Continue to fallback
        }

        // Method 3: Try monkey command to launch the app
        try {
            val process = Runtime.getRuntime().exec(arrayOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return jsonSuccess("Launched via monkey: $packageName", null)
            }
        } catch (e: Exception) {
            // Continue
        }

        return jsonError("Cannot launch app: $packageName - No launchable activity found")
    }

    private fun handleAppUninstall(params: Map<String, String>): Response {
        val packageName = params["packageName"] ?: return jsonError("packageName is required")
        val context = App.instance ?: return jsonError("App context not available")

        // Method 1: Try pm uninstall (silent, works with Device Owner)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", packageName))
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.contains("Success")) {
                return jsonSuccess("Uninstalled: $packageName", null)
            }
        } catch (e: Exception) {
            // Continue to fallback
        }

        // Method 2: Try ACTION_DELETE intent
        try {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return jsonSuccess("Uninstall dialog opened for: $packageName", null)
        } catch (e: Exception) {
            return jsonError("Failed to uninstall: ${e.message}")
        }
    }

    private fun handleAppRestrictWifi(params: Map<String, String>): Response {
        val packageName = params["packageName"] ?: return jsonError("packageName is required")
        val enable = params["enable"]?.toBoolean() ?: false

        // Method 1: Try pm disable/enable (requires Device Owner)
        try {
            val process = if (enable) {
                Runtime.getRuntime().exec(arrayOf("pm", "enable", packageName))
            } else {
                Runtime.getRuntime().exec(arrayOf("pm", "disable", packageName))
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // Verify the change
                val context = App.instance
                if (context != null) {
                    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    val nowEnabled = appInfo.enabled
                    if (enable == nowEnabled) {
                        return jsonSuccess(
                            if (enable) "Enabled: $packageName" else "Disabled: $packageName",
                            null
                        )
                    }
                }
                return jsonSuccess(
                    if (enable) "Enable command sent for: $packageName" else "Disable command sent for: $packageName",
                    null
                )
            }
        } catch (e: Exception) {
            // Continue to fallback
        }

        return jsonError("Cannot restrict app: Requires Device Owner. Set this app as Device Owner to use this feature.")
    }

    // ============ WiFi Control ============

    private fun handleWifiControl(params: Map<String, String>): Response {
        val action = params["action"] ?: return jsonError("action is required (enable/disable)")
        val context = App.instance ?: return jsonError("App context not available")

        when (action) {
            "enable" -> {
                // Method 1: svc wifi enable (works with Device Owner)
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "enable"))
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        return jsonSuccess("WiFi enabled", null)
                    }
                } catch (e: Exception) {
                    // Continue to fallback
                }

                // Method 2: WifiManager.setWifiEnabled() (pre-Android 10)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        val result = wifiManager.setWifiEnabled(true)
                        if (result) {
                            return jsonSuccess("WiFi enabled", null)
                        }
                    } catch (e: Exception) {
                        // Continue to fallback
                    }
                }

                // Method 3: Open WiFi settings panel (Android 10+)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
                        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(panelIntent)
                        return jsonSuccess("WiFi settings panel opened", null)
                    }
                } catch (e: Exception) {
                    // Continue
                }

                return jsonError("Cannot enable WiFi: Requires Device Owner or pre-Android 10")
            }

            "disable" -> {
                // Method 1: svc wifi disable (works with Device Owner)
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "disable"))
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        return jsonSuccess("WiFi disabled", null)
                    }
                } catch (e: Exception) {
                    // Continue to fallback
                }

                // Method 2: WifiManager.setWifiEnabled(false) (pre-Android 10)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        val result = wifiManager.setWifiEnabled(false)
                        if (result) {
                            return jsonSuccess("WiFi disabled", null)
                        }
                    } catch (e: Exception) {
                        // Continue to fallback
                    }
                }

                return jsonError("Cannot disable WiFi: Requires Device Owner or pre-Android 10")
            }

            else -> return jsonError("Unknown WiFi action: $action. Use enable or disable.")
        }
    }

    // ============ Play Audio ============

    private fun handlePlayAudio(session: IHTTPSession): Response {
        val context = App.instance ?: return jsonError("App context not available")

        try {
            val files = HashMap<String, String>()
            val params = HashMap<String, String>()
            session.parseBody(files)

            val tempFilePath = files["file"] ?: files["audio"] ?: files.entries.firstOrNull()?.value
                ?: return jsonError("No audio file provided")

            val tempFile = File(tempFilePath)

            // Save to a more permanent temp location
            val audioDir = File(context.cacheDir, "p2p_audio")
            if (!audioDir.exists()) audioDir.mkdirs()

            // Clean up old audio files
            audioDir.listFiles()?.forEach { it.delete() }

            val audioFile = File(audioDir, "play_${System.currentTimeMillis()}.tmp")
            tempFile.copyTo(audioFile)

            // Stop any currently playing audio
            stopCurrentAudio()

            // Play the audio file
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    audioFile.delete()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    audioFile.delete()
                    true
                }
                prepare()
                start()
            }

            return jsonSuccess("Audio playing", mapOf("file" to audioFile.name))
        } catch (e: Exception) {
            return jsonError("Failed to play audio: ${e.message}")
        }
    }

    private fun handleStopAudio(): Response {
        return try {
            stopCurrentAudio()
            jsonSuccess("Audio stopped", null)
        } catch (e: Exception) {
            jsonError("Failed to stop audio: ${e.message}")
        }
    }

    private fun stopCurrentAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            mediaPlayer = null
        }

        // Clean up audio temp files
        try {
            val context = App.instance
            if (context != null) {
                val audioDir = File(context.cacheDir, "p2p_audio")
                audioDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    // ============ Helper Methods ============

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
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".ogg") -> "audio/ogg"
            name.endsWith(".flac") -> "audio/flac"
            name.endsWith(".m4a") -> "audio/mp4"
            name.endsWith(".aac") -> "audio/aac"
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

package com.p2pfileshare.app.server

import com.google.gson.Gson
import com.p2pfileshare.app.App
import com.p2pfileshare.app.model.ApiResponse
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
                uri == "/api/screenshot" -> handleScreenshot()
                uri == "/api/touch" && method == Method.POST -> handleTouch(params)
                uri == "/api/screen-info" -> handleScreenInfo()
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
            return jsonError("Target directory not found: $targetPath")
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

    private fun handleScreenshot(): Response {
        if (prefs.isLocked) {
            return jsonError("This device is locked", Response.Status.FORBIDDEN)
        }
        val capture = com.p2pfileshare.app.remote.ScreenCaptureManager.instance
        val bitmap = capture?.getLatestBitmap()
        if (bitmap == null) {
            return jsonError("Screen capture not active. Enable in Settings > Remote Control.")
        }
        try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()
            val inputStream = java.io.ByteArrayInputStream(bytes)
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", inputStream, bytes.size.toLong())
        } catch (e: Exception) {
            return jsonError("Failed to capture screen: ${e.message}")
        }
    }

    private fun handleTouch(params: Map<String, String>): Response {
        if (prefs.isLocked) {
            return jsonError("This device is locked", Response.Status.FORBIDDEN)
        }
        val xStr = params["x"] ?: return jsonError("x is required")
        val yStr = params["y"] ?: return jsonError("y is required")
        val action = params["action"] ?: "tap"
        val x = xStr.toFloatOrNull() ?: return jsonError("Invalid x")
        val y = yStr.toFloatOrNull() ?: return jsonError("Invalid y")

        val gestureService = com.p2pfileshare.app.remote.RemoteGestureService.instance
        if (gestureService == null) {
            return jsonError("Gesture service not active. Enable Accessibility in Settings.")
        }

        val success = when (action) {
            "tap" -> gestureService.dispatchTap(x, y)
            "long_press" -> gestureService.dispatchLongPress(x, y)
            "swipe" -> {
                val dx = params["dx"]?.toFloatOrNull() ?: 0f
                val dy = params["dy"]?.toFloatOrNull() ?: 0f
                gestureService.dispatchSwipe(x, y, x + dx, y + dy)
            }
            else -> false
        }

        return if (success) jsonSuccess("Gesture dispatched", action) 
               else jsonError("Failed to dispatch gesture")
    }

    private fun handleScreenInfo(): Response {
        if (prefs.isLocked) {
            return jsonError("This device is locked", Response.Status.FORBIDDEN)
        }
        val context = App.instance ?: return jsonError("App not initialized")
        val display = context.resources.displayMetrics
        val data = mapOf(
            "width" to display.widthPixels,
            "height" to display.heightPixels,
            "density" to display.densityDpi,
            "captureActive" to (com.p2pfileshare.app.remote.ScreenCaptureManager.instance?.isCapturing() == true),
            "gestureActive" to (com.p2pfileshare.app.remote.RemoteGestureService.instance != null)
        )
        return jsonSuccess("OK", data)
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

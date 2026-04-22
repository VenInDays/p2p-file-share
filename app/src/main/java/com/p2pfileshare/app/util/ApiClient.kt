package com.p2pfileshare.app.util

import android.util.Log
import com.google.gson.Gson
import com.p2pfileshare.app.model.ApiResponse
import com.p2pfileshare.app.model.DirectoryInfo
import com.p2pfileshare.app.model.PeerDevice
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URLEncoder

class ApiClient {
    private val gson = Gson()
    private val tag = "ApiClient"

    companion object {
        /** Known text file extensions that can be edited */
        val TEXT_EXTENSIONS = setOf(
            ".txt", ".log", ".md", ".json", ".xml", ".html", ".htm", ".css", ".js",
            ".csv", ".properties", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf",
            ".sh", ".bat", ".ps1", ".py", ".java", ".kt", ".c", ".cpp", ".h",
            ".hpp", ".cs", ".rb", ".php", ".pl", ".sql", ".gradle", ".gitignore",
            ".dockerignore", ".env", ".ts", ".tsx", ".jsx", ".vue", ".svelte",
            ".scss", ".sass", ".less", ".rst", ".tex", ".svg", ".xaml", ".rs",
            ".go", ".swift", ".dart", ".lua", ".r", ".m", ".mm"
        )

        val IMAGE_EXTENSIONS = setOf(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tiff", ".tif", ".ico"
        )

        val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".3gp", ".webm", ".mkv", ".avi", ".mov", ".flv", ".wmv"
        )

        val AUDIO_EXTENSIONS = setOf(
            ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".wma"
        )

        fun isTextFile(name: String): Boolean {
            val lower = name.lowercase()
            return TEXT_EXTENSIONS.any { lower.endsWith(it) }
        }

        fun isImageFile(name: String): Boolean {
            val lower = name.lowercase()
            return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        }

        fun isVideoFile(name: String): Boolean {
            val lower = name.lowercase()
            return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
        }

        fun isAudioFile(name: String): Boolean {
            val lower = name.lowercase()
            return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
        }

        fun isPreviewable(name: String): Boolean {
            return isImageFile(name) || isVideoFile(name)
        }
    }

    suspend fun getInfo(peer: PeerDevice): ApiResponse? = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("http://${peer.host}:${peer.port}/api/info")
            gson.fromJson(json, ApiResponse::class.java)
        } catch (e: Exception) {
            Log.e(tag, "getInfo failed", e)
            null
        }
    }

    suspend fun listFiles(peer: PeerDevice, path: String = "/"): DirectoryInfo? = withContext(Dispatchers.IO) {
        try {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val json = httpGet("http://${peer.host}:${peer.port}/api/list?path=$encodedPath")
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
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val url = URL("http://${peer.host}:${peer.port}/api/download?path=$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000

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

    /** Download file to a specific target file (for preview caching) */
    suspend fun downloadFileTo(peer: PeerDevice, path: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val url = URL("http://${peer.host}:${peer.port}/api/download?path=$encodedPath")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000

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
            Log.e(tag, "downloadFileTo failed", e)
            false
        }
    }

    suspend fun uploadFile(peer: PeerDevice, file: File, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val boundary = "----P2PBoundary${System.currentTimeMillis()}"
            val url = URL("http://${peer.host}:${peer.port}/api/upload?path=${URLEncoder.encode(targetPath, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
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
            val json = httpGet("http://${peer.host}:${peer.port}/api/create-folder?path=${URLEncoder.encode(path, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}")
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "createFolder failed", e)
            false
        }
    }

    suspend fun createFile(peer: PeerDevice, path: String, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("http://${peer.host}:${peer.port}/api/create-file?path=${URLEncoder.encode(path, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}")
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "createFile failed", e)
            false
        }
    }

    suspend fun deleteFile(peer: PeerDevice, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.host}:${peer.port}/api/delete?path=${URLEncoder.encode(path, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

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
            val json = httpGet("http://${peer.host}:${peer.port}/api/rename?oldPath=${URLEncoder.encode(oldPath, "UTF-8")}&newName=${URLEncoder.encode(newName, "UTF-8")}")
            val response = gson.fromJson(json, ApiResponse::class.java)
            response.success
        } catch (e: Exception) {
            Log.e(tag, "renameFile failed", e)
            false
        }
    }

    suspend fun editFile(peer: PeerDevice, path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val boundary = "----P2PBoundary${System.currentTimeMillis()}"
            val url = URL("http://${peer.host}:${peer.port}/api/edit")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
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
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            httpGet("http://${peer.host}:${peer.port}/api/download?path=$encodedPath")
        } catch (e: Exception) {
            Log.e(tag, "getFileContent failed", e)
            null
        }
    }

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000

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

    // ===== Remote Control API =====

    data class ScreenInfo(
        val width: Int = 720,
        val height: Int = 1280,
        val density: Int = 160,
        val captureActive: Boolean = false,
        val gestureActive: Boolean = false
    )

    suspend fun getScreenInfo(peer: PeerDevice): ScreenInfo? = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("http://${peer.host}:${peer.port}/api/screen-info")
            val response = gson.fromJson(json, ApiResponse::class.java)
            if (response.success && response.data != null) {
                val dataMap = gson.fromJson(gson.toJson(response.data), Map::class.java)
                ScreenInfo(
                    width = (dataMap["width"] as? Double)?.toInt() ?: 720,
                    height = (dataMap["height"] as? Double)?.toInt() ?: 1280,
                    density = (dataMap["density"] as? Double)?.toInt() ?: 160,
                    captureActive = dataMap["captureActive"] as? Boolean ?: false,
                    gestureActive = dataMap["gestureActive"] as? Boolean ?: false
                )
            } else null
        } catch (e: Exception) {
            Log.e(tag, "getScreenInfo failed", e)
            null
        }
    }

    suspend fun getScreenshot(peer: PeerDevice): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.host}:${peer.port}/api/screenshot")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000

            val inputStream = conn.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            conn.disconnect()
            bitmap
        } catch (e: Exception) {
            Log.e(tag, "getScreenshot failed", e)
            null
        }
    }

    suspend fun sendTouch(peer: PeerDevice, x: Float, y: Float, action: String = "tap"): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.host}:${peer.port}/api/touch?x=$x&y=$y&action=${URLEncoder.encode(action, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000

            // Write empty body for POST
            conn.outputStream.write(ByteArray(0))
            conn.outputStream.flush()

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            Log.e(tag, "sendTouch failed", e)
            false
        }
    }

    suspend fun sendSwipe(peer: PeerDevice, x: Float, y: Float, dx: Float, dy: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://${peer.host}:${peer.port}/api/touch?x=$x&y=$y&action=swipe&dx=$dx&dy=$dy")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000

            conn.outputStream.write(ByteArray(0))
            conn.outputStream.flush()

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            Log.e(tag, "sendSwipe failed", e)
            false
        }
    }
}

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
import java.net.URLEncoder

class ApiClient {
    private val gson = Gson()
    private val tag = "ApiClient"

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
                // Write file part
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
}

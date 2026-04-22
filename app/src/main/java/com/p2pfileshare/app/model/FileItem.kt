package com.p2pfileshare.app.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class FileItem(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("isDirectory") val isDirectory: Boolean,
    @SerializedName("size") val size: Long,
    @SerializedName("lastModified") val lastModified: Long,
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("readable") val readable: Boolean = true,
    @SerializedName("writable") val writable: Boolean = true
) : Serializable

data class PeerDevice(
    @SerializedName("name") val name: String,
    @SerializedName("host") val host: String,
    @SerializedName("port") val port: Int
) : Serializable

data class ApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: Any? = null
)

data class DirectoryInfo(
    @SerializedName("path") val path: String,
    @SerializedName("files") val files: List<FileItem>,
    @SerializedName("parent") val parent: String?
)

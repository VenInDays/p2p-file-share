package com.p2pfileshare.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.FileItem
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.util.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FilePreviewActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient

    // SubsamplingScaleImageView for large images (no OOM, no lag)
    private var subsamplingImageView: SubsamplingScaleImageView? = null
    private var imageView: ImageView? = null
    private var playerView: PlayerView? = null
    private var progressBar: ProgressBar? = null
    private var tvError: TextView? = null
    private var tvFileName: TextView? = null

    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var filePath: String = ""
    private var fileName: String = ""
    private var fileSize: Long = 0
    private var mimeType: String = ""

    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_preview)

        apiClient = ApiClient()

        imageView = findViewById(R.id.ivPreview)
        playerView = findViewById(R.id.playerView)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        tvFileName = findViewById(R.id.tvFileName)

        // Initialize SubsamplingScaleImageView programmatically
        subsamplingImageView = SubsamplingScaleImageView(this).apply {
            id = View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            setOnClickListener { /* allow tap interactions */ }
        }

        // Add SubsamplingScaleImageView to the FrameLayout parent
        val frameLayout = imageView?.parent as? android.widget.FrameLayout
        frameLayout?.addView(subsamplingImageView, 0)

        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        filePath = intent.getStringExtra("file_path") ?: ""
        fileName = intent.getStringExtra("file_name") ?: "Preview"
        fileSize = intent.getLongExtra("file_size", 0)
        mimeType = intent.getStringExtra("file_mime") ?: ""

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = fileName

        tvFileName?.text = fileName
        tvFileName?.visibility = View.GONE

        loadPreview()
    }

    private fun getFileUrl(): String {
        val encodedPath = URLEncoder.encode(filePath, "UTF-8")
        return "http://$peerHost:$peerPort/api/download?path=$encodedPath"
    }

    private fun loadPreview() {
        when {
            isImageFile(fileName) -> {
                imageView?.visibility = View.GONE
                subsamplingImageView?.visibility = View.VISIBLE
                playerView?.visibility = View.GONE
                loadImageWithSubsampling()
            }
            isVideoFile(fileName) -> {
                imageView?.visibility = View.GONE
                subsamplingImageView?.visibility = View.GONE
                playerView?.visibility = View.VISIBLE
                loadVideo(getFileUrl())
            }
            isTextFile(fileName) -> {
                imageView?.visibility = View.GONE
                subsamplingImageView?.visibility = View.GONE
                playerView?.visibility = View.GONE
                loadTextContent()
            }
            else -> {
                showUnsupportedPreview()
            }
        }
    }

    /**
     * Load image using SubsamplingScaleImageView for large images.
     * This prevents OOM and lag even with very large images (300KB+ or multi-MB).
     * Downloads to a temp file then uses tiling for smooth rendering.
     */
    private fun loadImageWithSubsampling() {
        progressBar?.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val tempFile = downloadToTempFile()
                withContext(Dispatchers.Main) {
                    progressBar?.visibility = View.GONE
                    if (tempFile != null && tempFile.exists()) {
                        // Use SubsamplingScaleImageView for efficient rendering
                        subsamplingImageView?.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(android.net.Uri.fromFile(tempFile)))
                        subsamplingImageView?.visibility = View.VISIBLE
                    } else {
                        // Fallback to regular ImageView with Coil for smaller images
                        loadImageWithCoil()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar?.visibility = View.GONE
                    // Fallback to Coil
                    loadImageWithCoil()
                }
            }
        }
    }

    /**
     * Download file to a temporary file for SubsamplingScaleImageView.
     */
    private suspend fun downloadToTempFile(): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(getFileUrl())
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000

            // Add token header if available
            val peer = PeerDevice("", peerHost, peerPort)
            val token = apiClient.getPeerToken(peer)
            if (token.isNotEmpty()) {
                conn.setRequestProperty("x-p2p-token", token)
            }

            val tempFile = File(cacheDir, "preview_${System.currentTimeMillis()}_${fileName}")
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        output.write(buffer, 0, len)
                    }
                }
            }
            conn.disconnect()
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback: Load image with Coil (for small images or when SubsamplingScaleImageView fails).
     */
    private fun loadImageWithCoil() {
        val url = getFileUrl()
        imageView?.visibility = View.VISIBLE
        subsamplingImageView?.visibility = View.GONE
        progressBar?.visibility = View.VISIBLE

        try {
            val imageLoader = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(url)
                .crossfade(true)
                .size(1024, 1024) // Limit size to prevent OOM
                .target(
                    onStart = {
                        progressBar?.visibility = View.VISIBLE
                    },
                    onSuccess = { drawable ->
                        progressBar?.visibility = View.GONE
                        imageView?.setImageDrawable(drawable)
                    },
                    onError = {
                        progressBar?.visibility = View.GONE
                        showError("Không thể tải ảnh")
                    }
                )
                .build()
            imageLoader.enqueue(request)
        } catch (e: Exception) {
            progressBar?.visibility = View.GONE
            showError("Lỗi tải ảnh: ${e.message}")
        }
    }

    private fun loadVideo(url: String) {
        try {
            progressBar?.visibility = View.VISIBLE
            exoPlayer = ExoPlayer.Builder(this).build()
            playerView?.player = exoPlayer

            val mediaItem = MediaItem.fromUri(url)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        com.google.android.exoplayer2.Player.STATE_READY -> {
                            progressBar?.visibility = View.GONE
                        }
                        com.google.android.exoplayer2.Player.STATE_ENDED -> {
                            progressBar?.visibility = View.GONE
                        }
                        com.google.android.exoplayer2.Player.STATE_BUFFERING -> {
                            progressBar?.visibility = View.VISIBLE
                        }
                        else -> {}
                    }
                }
            })
        } catch (e: Exception) {
            progressBar?.visibility = View.GONE
            showError("Lỗi phát video: ${e.message}")
        }
    }

    private fun loadTextContent() {
        progressBar?.visibility = View.VISIBLE
        val peer = PeerDevice("", peerHost, peerPort)

        lifecycleScope.launch {
            try {
                val content = apiClient.getFileContent(peer, filePath)
                withContext(Dispatchers.Main) {
                    progressBar?.visibility = View.GONE
                    if (content != null) {
                        val intent = Intent(this@FilePreviewActivity, FileEditorActivity::class.java)
                        intent.putExtra("peer_host", peerHost)
                        intent.putExtra("peer_port", peerPort)
                        intent.putExtra("file_path", filePath)
                        intent.putExtra("file_name", fileName)
                        intent.putExtra("file_content", content)
                        startActivity(intent)
                        finish()
                    } else {
                        showError("Không thể đọc file")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar?.visibility = View.GONE
                    showError("Lỗi: ${e.message}")
                }
            }
        }
    }

    private fun showUnsupportedPreview() {
        imageView?.visibility = View.GONE
        subsamplingImageView?.visibility = View.GONE
        playerView?.visibility = View.GONE
        progressBar?.visibility = View.GONE

        val sizeStr = formatFileSize(fileSize)
        tvError?.text = "Xem trước không hỗ trợ\n\nTên: $fileName\nKích thước: $sizeStr\nLoại: $mimeType"
        tvError?.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        tvError?.text = message
        tvError?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE
        subsamplingImageView?.visibility = View.GONE
        playerView?.visibility = View.GONE
        progressBar?.visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.preview_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_download_preview -> {
                downloadCurrentFile()
                true
            }
            R.id.action_edit_preview -> {
                loadTextContent()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun downloadCurrentFile() {
        val peer = PeerDevice("", peerHost, peerPort)
        val file = FileItem(fileName, filePath, false, fileSize, 0, mimeType)
        val destDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

        progressBar?.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = apiClient.downloadFile(peer, filePath, destDir)
            withContext(Dispatchers.Main) {
                progressBar?.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@FilePreviewActivity, "Đã tải xuống: $fileName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FilePreviewActivity, "Tải xuống thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            // Ignore
        }
        // Clean up temp preview files
        try {
            cacheDir.listFiles()?.filter { it.name.startsWith("preview_") }?.forEach { it.delete() }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    companion object {
        fun isImageFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                    n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp")
        }

        fun isVideoFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".webm") ||
                    n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
        }

        fun isTextFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md") ||
                    n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".html") ||
                    n.endsWith(".css") || n.endsWith(".js") || n.endsWith(".csv") ||
                    n.endsWith(".properties") || n.endsWith(".yml") || n.endsWith(".yaml") ||
                    n.endsWith(".sh") || n.endsWith(".py") || n.endsWith(".java") ||
                    n.endsWith(".kt") || n.endsWith(".c") || n.endsWith(".cpp") ||
                    n.endsWith(".h") || n.endsWith(".cfg") || n.endsWith(".conf") ||
                    n.endsWith(".ini") || n.endsWith(".gradle") || n.endsWith(".toml") ||
                    n.endsWith(".sql") || n.endsWith(".rb") || n.endsWith(".php") ||
                    n.endsWith(".swift") || n.endsWith(".go") || n.endsWith(".rs")
        }

        fun isAudioFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") ||
                    n.endsWith(".ogg") || n.endsWith(".aac") || n.endsWith(".m4a")
        }

        fun isPreviewable(name: String): Boolean {
            return isImageFile(name) || isVideoFile(name) || isTextFile(name) || isAudioFile(name)
        }
    }
}

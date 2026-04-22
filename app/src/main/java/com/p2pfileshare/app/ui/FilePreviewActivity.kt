package com.p2pfileshare.app.ui

import android.content.Intent
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
import java.net.URLEncoder

class FilePreviewActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient

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
        val url = getFileUrl()

        when {
            isImageFile(fileName) -> {
                imageView?.visibility = View.VISIBLE
                playerView?.visibility = View.GONE
                loadImage(url)
            }
            isVideoFile(fileName) -> {
                imageView?.visibility = View.GONE
                playerView?.visibility = View.VISIBLE
                loadVideo(url)
            }
            isTextFile(fileName) -> {
                // Text files: load content and show
                imageView?.visibility = View.GONE
                playerView?.visibility = View.GONE
                loadTextContent()
            }
            else -> {
                // Unsupported preview - show file info
                showUnsupportedPreview()
            }
        }
    }

    private fun loadImage(url: String) {
        progressBar?.visibility = View.VISIBLE
        try {
            val imageLoader = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(url)
                .crossfade(true)
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
                        // Open editor for text files
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
                if (isTextFile(fileName)) {
                    loadTextContent()
                } else {
                    Toast.makeText(this, "Chỉ có thể chỉnh sửa file văn bản", Toast.LENGTH_SHORT).show()
                }
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

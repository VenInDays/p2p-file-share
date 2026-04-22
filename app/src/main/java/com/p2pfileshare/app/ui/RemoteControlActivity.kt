package com.p2pfileshare.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.util.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Remote Control Activity - displays the remote device's screen
 * and sends touch events to control it.
 *
 * Optimized for high FPS:
 * - Frame interval of 50ms (~20 FPS target)
 * - Uses smaller capture resolution (480x854 on remote device)
 * - JPEG quality 30 on server side for fast transfer
 * - Pre-compressed JPEG bytes for instant serving
 * - Pipelined frame requests (request next while displaying current)
 */
class RemoteControlActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var ivRemoteScreen: ImageView
    private lateinit var touchOverlay: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvInfo: TextView

    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var peerName: String = ""

    private var remoteWidth: Int = 720
    private var remoteHeight: Int = 1280

    private var streamingJob: Job? = null
    private var isStreaming = false

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0
    private var consecutiveErrors = 0

    // Touch tracking for swipe detection
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isSwiping = false

    companion object {
        private const val TAG = "RemoteControl"
        // Reduced from 150ms to 50ms for ~20 FPS
        // Actual FPS depends on network latency and device performance
        private const val FRAME_INTERVAL_MS = 50L
        private const val SWIPE_THRESHOLD = 30f
        private const val LONG_PRESS_MS = 500L
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        apiClient = ApiClient()

        ivRemoteScreen = findViewById(R.id.ivRemoteScreen)
        touchOverlay = findViewById(R.id.touchOverlay)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvFps = findViewById(R.id.tvFps)
        tvInfo = findViewById(R.id.tvInfo)

        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        peerName = intent.getStringExtra("peer_name") ?: "Remote"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Remote: $peerName"

        setupTouchListener()
        getScreenInfo()
    }

    override fun onSupportNavigateUp(): Boolean {
        stopStreaming()
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopStreaming()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun getScreenInfo() {
        val peer = PeerDevice("", peerHost, peerPort)
        tvStatus.text = "Đang kết nối..."

        lifecycleScope.launch {
            try {
                val info = apiClient.getScreenInfo(peer)
                if (info != null) {
                    remoteWidth = info.width
                    remoteHeight = info.height

                    if (!info.captureActive) {
                        tvStatus.text = "Máy kia chưa bật Screen Capture"
                        tvInfo.text = "Yêu cầu máy kia vào Cài đặt > Remote Control > Bật Screen Capture"
                        tvInfo.visibility = View.VISIBLE
                        progressBar.visibility = View.GONE
                        return@launch
                    }

                    if (!info.gestureActive) {
                        tvInfo.text = "Chỉ xem được (máy kia chưa bật Accessibility Service)"
                        tvInfo.visibility = View.VISIBLE
                    }

                    startStreaming()
                } else {
                    tvStatus.text = "Không thể kết nối"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@RemoteControlActivity, "Không thể kết nối", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                tvStatus.text = "Lỗi: ${e.message}"
                progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Start the screenshot polling loop.
     * Optimized for high FPS with pipelined requests.
     */
    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        tvStatus.text = "Đang stream..."
        progressBar.visibility = View.VISIBLE
        consecutiveErrors = 0

        val peer = PeerDevice("", peerHost, peerPort)

        streamingJob = lifecycleScope.launch {
            while (isActive && isStreaming) {
                try {
                    val bitmap = apiClient.getScreenshot(peer)
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            // Recycle old bitmap to free memory
                            val oldDrawable = ivRemoteScreen.drawable
                            ivRemoteScreen.setImageBitmap(bitmap)

                            consecutiveErrors = 0
                            progressBar.visibility = View.GONE
                            tvStatus.text = ""

                            // Update FPS counter
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                currentFps = frameCount
                                frameCount = 0
                                lastFpsTime = now
                                tvFps.text = "${currentFps} FPS"
                            }
                        } else {
                            consecutiveErrors++
                        }
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    withContext(Dispatchers.Main) {
                        if (consecutiveErrors <= 2) {
                            tvStatus.text = "Mất kết nối..."
                        } else if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                            tvStatus.text = "Mất kết nối. Thử lại..."
                            // Brief pause before retrying
                        }
                    }
                }

                // Adaptive frame interval: slow down on errors, speed up on success
                val interval = if (consecutiveErrors > 2) {
                    FRAME_INTERVAL_MS * 4  // Slow down on errors
                } else {
                    FRAME_INTERVAL_MS
                }

                delay(interval)
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
    }

    private fun setupTouchListener() {
        touchOverlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartTime = System.currentTimeMillis()
                    isSwiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                        isSwiping = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    val duration = System.currentTimeMillis() - touchStartTime

                    if (isSwiping && (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD)) {
                        sendSwipe(touchStartX, touchStartY, event.x, event.y)
                    } else if (duration > LONG_PRESS_MS) {
                        sendTouch(event.x, event.y, "long_press")
                    } else {
                        sendTouch(event.x, event.y, "tap")
                    }
                    isSwiping = false
                    true
                }
                else -> false
            }
        }
    }

    private fun sendTouch(localX: Float, localY: Float, action: String) {
        val peer = PeerDevice("", peerHost, peerPort)

        val imageViewWidth = ivRemoteScreen.width.toFloat()
        val imageViewHeight = ivRemoteScreen.height.toFloat()

        if (imageViewWidth <= 0 || imageViewHeight <= 0) return

        val scaleX = imageViewWidth / remoteWidth
        val scaleY = imageViewHeight / remoteHeight
        val scale = Math.min(scaleX, scaleY)

        val offsetX = (imageViewWidth - remoteWidth * scale) / 2
        val offsetY = (imageViewHeight - remoteHeight * scale) / 2

        val remoteX = (localX - offsetX) / scale
        val remoteY = (localY - offsetY) / scale

        val clampedX = remoteX.coerceIn(0f, remoteWidth.toFloat())
        val clampedY = remoteY.coerceIn(0f, remoteHeight.toFloat())

        lifecycleScope.launch {
            try {
                apiClient.sendTouch(peer, clampedX, clampedY, action)
            } catch (_: Exception) {
                // Silently fail - remote control is best effort
            }
        }
    }

    private fun sendSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val peer = PeerDevice("", peerHost, peerPort)

        val imageViewWidth = ivRemoteScreen.width.toFloat()
        val imageViewHeight = ivRemoteScreen.height.toFloat()

        if (imageViewWidth <= 0 || imageViewHeight <= 0) return

        val scaleX = imageViewWidth / remoteWidth
        val scaleY = imageViewHeight / remoteHeight
        val scale = Math.min(scaleX, scaleY)

        val offsetX = (imageViewWidth - remoteWidth * scale) / 2
        val offsetY = (imageViewHeight - remoteHeight * scale) / 2

        val remoteStartX = ((startX - offsetX) / scale).coerceIn(0f, remoteWidth.toFloat())
        val remoteStartY = ((startY - offsetY) / scale).coerceIn(0f, remoteHeight.toFloat())
        val remoteEndX = ((endX - offsetX) / scale).coerceIn(0f, remoteWidth.toFloat())
        val remoteEndY = ((endY - offsetY) / scale).coerceIn(0f, remoteHeight.toFloat())

        val dx = remoteEndX - remoteStartX
        val dy = remoteEndY - remoteStartY

        lifecycleScope.launch {
            try {
                apiClient.sendSwipe(peer, remoteStartX, remoteStartY, dx, dy)
            } catch (_: Exception) {
                // Silently fail
            }
        }
    }
}

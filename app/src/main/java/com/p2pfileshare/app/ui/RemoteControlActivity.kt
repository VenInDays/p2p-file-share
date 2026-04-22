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
 * Features:
 * - Real-time screen mirroring via periodic screenshot polling
 * - Touch/tap/long-press/swipe relay to remote device
 * - FPS indicator and connection status
 * - Respects "Lock this phone" on remote (server rejects if locked)
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

    // Touch tracking for swipe detection
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isSwiping = false

    companion object {
        private const val TAG = "RemoteControl"
        private const val FRAME_INTERVAL_MS = 150L  // ~6-7 FPS
        private const val SWIPE_THRESHOLD = 30f      // min distance for swipe
        private const val LONG_PRESS_MS = 500L       // long press threshold
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        apiClient = ApiClient()

        // Bind views
        ivRemoteScreen = findViewById(R.id.ivRemoteScreen)
        touchOverlay = findViewById(R.id.touchOverlay)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvFps = findViewById(R.id.tvFps)
        tvInfo = findViewById(R.id.tvInfo)

        // Get peer info
        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        peerName = intent.getStringExtra("peer_name") ?: "Remote"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Remote: $peerName"

        // Setup touch listener
        setupTouchListener()

        // Get screen info and start streaming
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

    /**
     * Get the remote device's screen dimensions and check if
     * screen capture and gesture service are active.
     */
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

                    // Start streaming
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
     */
    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        tvStatus.text = "Đang stream..."
        progressBar.visibility = View.VISIBLE

        val peer = PeerDevice("", peerHost, peerPort)

        streamingJob = lifecycleScope.launch {
            while (isActive && isStreaming) {
                try {
                    val bitmap = apiClient.getScreenshot(peer)
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            ivRemoteScreen.setImageBitmap(bitmap)
                            progressBar.visibility = View.GONE
                            tvStatus.text = ""

                            // Update FPS
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                currentFps = frameCount
                                frameCount = 0
                                lastFpsTime = now
                                tvFps.text = "${currentFps} FPS"
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        // Don't show error on every frame, just update status
                        tvStatus.text = "Mất kết nối..."
                    }
                }

                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * Setup touch listener on the overlay to capture taps, long presses, and swipes.
     * Maps local coordinates to remote screen coordinates.
     */
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
                        // Swipe gesture
                        sendSwipe(touchStartX, touchStartY, event.x, event.y)
                    } else if (duration > LONG_PRESS_MS) {
                        // Long press
                        sendTouch(event.x, event.y, "long_press")
                    } else {
                        // Tap
                        sendTouch(event.x, event.y, "tap")
                    }
                    isSwiping = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Map local touch coordinates to remote screen coordinates and send.
     */
    private fun sendTouch(localX: Float, localY: Float, action: String) {
        val peer = PeerDevice("", peerHost, peerPort)

        // Calculate the image display bounds within the ImageView
        val imageViewWidth = ivRemoteScreen.width.toFloat()
        val imageViewHeight = ivRemoteScreen.height.toFloat()

        if (imageViewWidth <= 0 || imageViewHeight <= 0) return

        // Calculate scale and offset (fitCenter)
        val scaleX = imageViewWidth / remoteWidth
        val scaleY = imageViewHeight / remoteHeight
        val scale = Math.min(scaleX, scaleY)

        val offsetX = (imageViewWidth - remoteWidth * scale) / 2
        val offsetY = (imageViewHeight - remoteHeight * scale) / 2

        // Map local coordinates to remote coordinates
        val remoteX = (localX - offsetX) / scale
        val remoteY = (localY - offsetY) / scale

        // Clamp to screen bounds
        val clampedX = remoteX.coerceIn(0f, remoteWidth.toFloat())
        val clampedY = remoteY.coerceIn(0f, remoteHeight.toFloat())

        lifecycleScope.launch {
            try {
                apiClient.sendTouch(peer, clampedX, clampedY, action)
            } catch (e: Exception) {
                // Silently fail - remote control is best effort
            }
        }
    }

    /**
     * Send a swipe gesture to the remote device.
     */
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
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }
}

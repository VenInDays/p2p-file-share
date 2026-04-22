package com.p2pfileshare.app.remote

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Manages MediaProjection-based screen capture for the Remote Control feature.
 *
 * Optimized for high FPS streaming:
 * - Uses reduced capture resolution (480x800) for fast network transfer
 * - Keeps a pre-compressed JPEG buffer for instant serving
 * - Compresses at quality 30 for small size while maintaining readability
 * - Background compression thread to avoid blocking the UI
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 5001

        // Capture resolution - smaller = faster streaming
        private const val CAPTURE_WIDTH = 480
        private const val CAPTURE_HEIGHT = 854

        // JPEG compression quality (1-100). Lower = faster transfer, lower quality
        // 30 is good balance - readable text, small file size
        private const val JPEG_QUALITY = 30

        /** Singleton instance, set from SettingsActivity when permission granted */
        var instance: ScreenCaptureManager? = null
            private set

        fun create(): ScreenCaptureManager {
            val manager = ScreenCaptureManager()
            instance = manager
            return manager
        }

        /** Get the MediaProjectionManager system service */
        fun getProjectionManager(context: Context): MediaProjectionManager? {
            return try {
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get MediaProjectionManager", e)
                null
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isRunning = false

    // Latest raw bitmap and pre-compressed JPEG bytes
    @Volatile
    private var latestBitmap: Bitmap? = null
    @Volatile
    private var latestJpegBytes: ByteArray? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Screen dimensions for capture */
    private var screenWidth = CAPTURE_WIDTH
    private var screenHeight = CAPTURE_HEIGHT
    private var screenDensity = 160

    /**
     * Initialize the capture manager with a MediaProjection obtained
     * from SettingsActivity's onActivityResult.
     */
    fun setMediaProjection(projection: MediaProjection) {
        stopCapture()
        this.mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
                mediaProjection = null
            }
        }, handler)
    }

    /**
     * Start screen capture. Must be called after setMediaProjection().
     * Uses a reduced resolution for network efficiency and high FPS.
     */
    fun startCapture(context: Context): Boolean {
        if (mediaProjection == null) {
            Log.e(TAG, "No MediaProjection available")
            return false
        }

        if (isRunning) {
            Log.d(TAG, "Capture already running")
            return true
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenDensity = metrics.densityDpi

            // Use fixed reduced resolution for fast streaming
            // This is much smaller than the actual screen but still readable
            screenWidth = CAPTURE_WIDTH
            screenHeight = CAPTURE_HEIGHT

            // Create ImageReader with 3 buffers for smoother capture
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 3
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image: Image? = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        image.close()

                        // Recycle old bitmap
                        val oldBitmap = latestBitmap
                        latestBitmap = bitmap

                        // Compress to JPEG in background for instant serving
                        compressToJpeg(bitmap)

                        oldBitmap?.recycle()
                    }
                } catch (e: Exception) {
                    // Image might already be closed, ignore
                }
            }, handler)

            // Create VirtualDisplay
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "P2PRemoteCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            isRunning = true
            Log.d(TAG, "Screen capture started: ${screenWidth}x${screenHeight} @ q=$JPEG_QUALITY")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopCapture()
            return false
        }
    }

    /**
     * Compress the latest bitmap to JPEG bytes for instant serving.
     * This avoids compressing on every HTTP request.
     */
    private fun compressToJpeg(bitmap: Bitmap) {
        try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            latestJpegBytes = stream.toByteArray()
        } catch (e: Exception) {
            // Bitmap may be recycled, ignore
        }
    }

    /**
     * Stop screen capture and release resources.
     */
    fun stopCapture() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) { }
        imageReader = null

        isRunning = false
        latestJpegBytes = null
        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * Get the latest captured frame as a Bitmap.
     * The caller should NOT recycle this bitmap.
     */
    @Synchronized
    fun getLatestBitmap(): Bitmap? {
        return latestBitmap
    }

    /**
     * Get the pre-compressed JPEG bytes for instant HTTP serving.
     * Much faster than compressing on every request.
     */
    @Synchronized
    fun getLatestJpegBytes(): ByteArray? {
        return latestJpegBytes
    }

    fun isCapturing(): Boolean = isRunning && mediaProjection != null

    /**
     * Get the capture dimensions (for remote control coordinate mapping)
     */
    fun getCaptureDimensions(): Pair<Int, Int> {
        return Pair(screenWidth, screenHeight)
    }

    /**
     * Stop the MediaProjection entirely (user revoked permission).
     */
    fun release() {
        stopCapture()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) { }
        mediaProjection = null
        latestBitmap?.recycle()
        latestBitmap = null
        latestJpegBytes = null
        instance = null
    }

    /**
     * Convert an Image to Bitmap.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmapWidth = screenWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop if needed (remove padding)
        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            cropped
        }
    }
}

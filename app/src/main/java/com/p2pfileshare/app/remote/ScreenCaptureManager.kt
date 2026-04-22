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
 * Flow:
 * 1. User grants MediaProjection permission via SettingsActivity
 * 2. The resulting MediaProjection is stored here
 * 3. startCapture() creates a VirtualDisplay + ImageReader
 * 4. Frames are captured on demand via getLatestBitmap()
 * 5. FileServer's /api/screenshot endpoint reads the latest frame
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 5001

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
    private var latestBitmap: Bitmap? = null
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())

    /** Screen dimensions for capture */
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 160

    /**
     * Initialize the capture manager with a MediaProjection obtained
     * from SettingsActivity's onActivityResult.
     */
    fun setMediaProjection(projection: MediaProjection) {
        stopCapture()
        this.mediaProjection = projection

        // Register callback to handle projection stopping
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
     * Uses a reduced resolution for network efficiency.
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
            // Get actual screen dimensions
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenDensity = metrics.densityDpi

            // Use reduced resolution for streaming (half the actual size for performance)
            @Suppress("DEPRECATION")
            screenWidth = Math.min(metrics.widthPixels, 720)
            @Suppress("DEPRECATION")
            screenHeight = Math.min(metrics.heightPixels, 1280)

            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image: Image? = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        image.close()

                        // Recycle old bitmap and store new one
                        latestBitmap?.recycle()
                        latestBitmap = bitmap
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
            Log.d(TAG, "Screen capture started: ${screenWidth}x${screenHeight}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopCapture()
            return false
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

    fun isCapturing(): Boolean = isRunning && mediaProjection != null

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

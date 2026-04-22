package com.p2pfileshare.app.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService that dispatches touch gestures for Remote Control.
 *
 * The user must enable this service manually in:
 * Settings > Accessibility > P2P File Share > Enable
 *
 * Once enabled, the service instance is available via the static `instance` field,
 * allowing the FileServer's /api/touch endpoint to dispatch gestures.
 */
class RemoteGestureService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteGesture"

        /** Current service instance, set when connected */
        var instance: RemoteGestureService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "RemoteGestureService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "RemoteGestureService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for gesture dispatch
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteGestureService interrupted")
    }

    /**
     * Dispatch a tap gesture at the given coordinates.
     */
    fun dispatchTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Tap dispatched at ($x, $y)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch tap", e)
            false
        }
    }

    /**
     * Dispatch a long press gesture at the given coordinates.
     */
    fun dispatchLongPress(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, 500)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Long press dispatched at ($x, $y)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch long press", e)
            false
        }
    }

    /**
     * Dispatch a swipe gesture from (startX, startY) to (endX, endY).
     */
    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            val stroke = GestureDescription.StrokeDescription(path, 0, 300)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Swipe dispatched from ($startX, $startY) to ($endX, $endY)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch swipe", e)
            false
        }
    }
}

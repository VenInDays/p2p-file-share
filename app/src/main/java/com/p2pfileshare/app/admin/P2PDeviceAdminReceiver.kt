package com.p2pfileshare.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver for P2P File Share.
 *
 * When this app is set as Device Owner (via ADB):
 * - It appears as a "System App" in Settings
 * - It CANNOT be uninstalled from Settings
 * - It can enforce security policies (password, lock screen, etc.)
 * - It can silently install/uninstall apps
 * - It can restrict WiFi access per-app
 * - It can disable camera, screen capture, etc.
 *
 * When only Device Admin (not Owner):
 * - Harder to uninstall (must deactivate admin first)
 * - Can enforce some security policies
 * - Cannot silently manage other apps
 *
 * Setup instructions:
 * 1. Install the APK normally
 * 2. Remove all accounts from device (Settings > Accounts) - required for Device Owner
 * 3. Run: adb shell dpm set-device-owner com.p2pfileshare.app/.admin.P2PDeviceAdminReceiver
 * 4. The app now appears as a System App and cannot be uninstalled
 */
class P2PDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "P2PDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d(TAG, "Password changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.d(TAG, "Password failed")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.d(TAG, "Password succeeded")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task mode entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task mode exiting")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete")
    }
}

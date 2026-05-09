package com.p2pfileshare.app.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Manages Device Admin and Device Owner functionality.
 *
 * Device Admin: Makes app harder to uninstall - must deactivate admin first.
 * Device Owner: Makes app appear as system app, completely prevents uninstall.
 *
 * To set as Device Owner via ADB:
 *   adb shell dpm set-device-owner com.p2pfileshare.app/.admin.P2PDeviceAdminReceiver
 *
 * To remove Device Owner:
 *   adb shell dpm remove-active-admin com.p2pfileshare.app/.admin.P2PDeviceAdminReceiver
 */
object DeviceAdminManager {

    private const val TAG = "DeviceAdminManager"

    /** Get the ComponentName for our DeviceAdminReceiver */
    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, P2PDeviceAdminReceiver::class.java)
    }

    /** Get the DevicePolicyManager system service */
    private fun getDpm(context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    /** Check if this app is currently a Device Admin */
    fun isAdminActive(context: Context): Boolean {
        return try {
            getDpm(context).isAdminActive(getAdminComponent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check admin status", e)
            false
        }
    }

    /** Check if this app is the Device Owner */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            getDpm(context).isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check device owner status", e)
            false
        }
    }

    /** Check if this app is a Profile Owner */
    fun isProfileOwner(context: Context): Boolean {
        return try {
            getDpm(context).isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check profile owner status", e)
            false
        }
    }

    /**
     * Open system settings to activate this app as Device Admin.
     * This shows the standard Android "Activate device admin" dialog.
     */
    fun activateDeviceAdmin(context: Context) {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent(context))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "P2P File Share cần quyền Device Admin để:\n" +
                    "• Ngăn chặn việc gỡ cài đặt app\n" +
                    "• Khóa màn hình từ xa\n" +
                    "• Quản lý mật khẩu thiết bị\n" +
                    "• Bảo vệ dữ liệu khi mất máy"
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate device admin", e)
            Toast.makeText(context, "Không thể mở cài đặt Device Admin", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Deactivate this app as Device Admin.
     * Only works if the app is NOT Device Owner (Device Owner cannot be deactivated this way).
     */
    fun deactivateDeviceAdmin(context: Context): Boolean {
        return try {
            if (isDeviceOwner(context)) {
                Toast.makeText(context,
                    "Không thể tắt! App là Device Owner.\nDùng ADB để g bỏ: adb shell dpm remove-active-admin ${context.packageName}/.admin.P2PDeviceAdminReceiver",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

            getDpm(context).removeActiveAdmin(getAdminComponent(context))
            Toast.makeText(context, "Đã tắt Device Admin", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate device admin", e)
            Toast.makeText(context, "Không thể tắt Device Admin: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Get the ADB command to set this app as Device Owner.
     * The user needs to run this command from a computer connected via USB.
     */
    fun getDeviceOwnerAdbCommand(context: Context): String {
        return "adb shell dpm set-device-owner ${context.packageName}/.admin.P2PDeviceAdminReceiver"
    }

    /**
     * Get the ADB command to remove Device Owner.
     */
    fun getRemoveDeviceOwnerAdbCommand(context: Context): String {
        return "adb shell dpm remove-active-admin ${context.packageName}/.admin.P2PDeviceAdminReceiver"
    }

    /**
     * Check if Device Owner can be set on this device.
     * Returns a status message explaining why or why not.
     */
    fun checkDeviceOwnerEligibility(context: Context): DeviceOwnerStatus {
        // Already device owner
        if (isDeviceOwner(context)) {
            return DeviceOwnerStatus.ALREADY_OWNER
        }

        // Already profile owner
        if (isProfileOwner(context)) {
            return DeviceOwnerStatus.IS_PROFILE_OWNER
        }

        // Check if another app is device owner
        try {
            val dpm = getDpm(context)
            // No direct API to check if another app is device owner, but we can try
        } catch (e: Exception) {
            // Ignore
        }

        // Check if device has accounts (Device Owner requires no accounts)
        val accountManager = android.accounts.AccountManager.get(context)
        val accounts = accountManager.accounts
        if (accounts.isNotEmpty()) {
            return DeviceOwnerStatus.HAS_ACCOUNTS
        }

        // Device admin needs to be active first
        if (!isAdminActive(context)) {
            return DeviceOwnerStatus.NEEDS_ADMIN_FIRST
        }

        return DeviceOwnerStatus.ELIGIBLE
    }

    /**
     * When Device Owner: enable WiFi using shell command.
     * Works on all Android versions for Device Owner apps.
     */
    fun enableWifi(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "enable"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable WiFi", e)
            false
        }
    }

    /**
     * When Device Owner: disable WiFi using shell command.
     * Works on all Android versions for Device Owner apps.
     */
    fun disableWifi(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "disable"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable WiFi", e)
            false
        }
    }

    /**
     * When Device Owner: silently uninstall an app using pm command.
     * This does NOT show any confirmation dialog.
     */
    fun silentUninstallApp(context: Context, packageName: String): Boolean {
        if (!isDeviceOwner(context)) return false

        return try {
            // Use pm uninstall which works silently for Device Owner
            val process = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", packageName))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            exitCode == 0 || output.contains("Success")
        } catch (e: Exception) {
            Log.e(TAG, "Silent uninstall failed for $packageName", e)
            false
        }
    }

    /**
     * When Device Owner: enable/disable an app (instead of uninstalling).
     * This hides the app but keeps it installed.
     */
    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean): Boolean {
        if (!isDeviceOwner(context) && !isAdminActive(context)) return false

        return try {
            // Use PackageManager to enable/disable - works for Device Owner without hidden APIs
            val state = if (enabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            context.packageManager.setApplicationEnabledSetting(packageName, state, 0)
            Log.d(TAG, "App $packageName ${if (enabled) "enabled" else "disabled"} successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ${if (enabled) "enable" else "disable"} app $packageName", e)
            // Fallback: use shell command
            try {
                val state = if (enabled) "enable" else "disable"
                val process = Runtime.getRuntime().exec(arrayOf("pm", state, packageName))
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (e2: Exception) {
                Log.e(TAG, "Shell fallback also failed", e2)
                false
            }
        }
    }

    /**
     * When Device Owner: lock the device immediately.
     */
    fun lockDevice(context: Context): Boolean {
        if (!isAdminActive(context)) return false

        return try {
            getDpm(context).lockNow()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device", e)
            false
        }
    }

    /**
     * When Device Owner: set a password for the device.
     */
    fun resetPassword(context: Context, newPassword: String, flags: Int = 0): Boolean {
        if (!isAdminActive(context)) return false

        return try {
            getDpm(context).resetPassword(newPassword, flags)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset password", e)
            false
        }
    }

    /**
     * When Device Owner: disable the camera.
     */
    fun setCameraDisabled(context: Context, disabled: Boolean): Boolean {
        if (!isAdminActive(context)) return false

        return try {
            getDpm(context).setCameraDisabled(getAdminComponent(context), disabled)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set camera disabled", e)
            false
        }
    }

    /**
     * When Device Owner: set lock screen message.
     */
    fun setLockScreenMessage(context: Context, message: String): Boolean {
        if (!isDeviceOwner(context)) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getDpm(context).setDeviceOwnerLockScreenInfo(getAdminComponent(context), message)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set lock screen message", e)
            false
        }
    }

    /**
     * Get current status summary for display.
     */
    fun getStatusSummary(context: Context): String {
        val isAdmin = isAdminActive(context)
        val isOwner = isDeviceOwner(context)
        val isProfile = isProfileOwner(context)

        return when {
            isOwner -> "DEVICE OWNER - Ứng dụng hệ thống\nApp không thể bị gỡ cài đặt. Xuất hiện trong 'Ứng dụng hệ thống'."
            isProfile -> "PROFILE OWNER\nApp quản lý hồ sơ công việc."
            isAdmin -> "DEVICE ADMIN\nApp khó bị gỡ hơn (phải tắt Device Admin trước)."
            else -> "Chưa bật\nBật Device Admin để bảo vệ app khỏi việc bị gỡ."
        }
    }

    enum class DeviceOwnerStatus {
        ALREADY_OWNER,       // Already device owner
        IS_PROFILE_OWNER,    // Already profile owner
        HAS_ACCOUNTS,        // Device has accounts - must remove first
        NEEDS_ADMIN_FIRST,   // Need to activate device admin first
        ELIGIBLE             // Can be set as device owner via ADB
    }
}

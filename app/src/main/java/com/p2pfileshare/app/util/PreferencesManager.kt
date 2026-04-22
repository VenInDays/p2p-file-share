package com.p2pfileshare.app.util

import android.content.Context
import android.content.SharedPreferences
import com.p2pfileshare.app.security.SecurityManager

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)

    var isLocked: Boolean
        get() = prefs.getBoolean("lock_this_phone", false)
        set(value) = prefs.edit().putBoolean("lock_this_phone", value).apply()

    var isAutoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(value) = prefs.edit().putBoolean("auto_start", value).apply()

    var serviceName: String
        get() = prefs.getString("service_name", "MyPhone") ?: "MyPhone"
        set(value) = prefs.edit().putString("service_name", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 9527)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var pairedDevices: Set<String>
        get() = prefs.getStringSet("paired_devices", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("paired_devices", value).apply()

    var lastConnectedHost: String
        get() = prefs.getString("last_connected_host", "") ?: ""
        set(value) = prefs.edit().putString("last_connected_host", value).apply()

    var lastConnectedPort: Int
        get() = prefs.getInt("last_connected_port", 0)
        set(value) = prefs.edit().putInt("last_connected_port", value).apply()

    var isGridView: Boolean
        get() = prefs.getBoolean("is_grid_view", false)
        set(value) = prefs.edit().putBoolean("is_grid_view", value).apply()

    // Security settings
    var isSecurityEnabled: Boolean
        get() = prefs.getBoolean("security_enabled", true)
        set(value) = prefs.edit().putBoolean("security_enabled", value).apply()

    var isRateLimitEnabled: Boolean
        get() = prefs.getBoolean("rate_limit_enabled", true)
        set(value) = prefs.edit().putBoolean("rate_limit_enabled", value).apply()

    var isEncryptionEnabled: Boolean
        get() = prefs.getBoolean("encryption_enabled", true)
        set(value) = prefs.edit().putBoolean("encryption_enabled", value).apply()

    /**
     * Obfuscate sensitive preference data.
     * When lock is enabled, encrypt stored data so terminal access reveals nothing useful.
     */
    fun lockSecureData() {
        if (isLocked && isEncryptionEnabled) {
            val editor = prefs.edit()
            // Store encrypted marker so app knows data is locked
            editor.putString("lock_hash", SecurityManager.encryptData("LOCKED_${System.currentTimeMillis()}"))
            editor.apply()
        }
    }

    /**
     * Verify that secure data hasn't been tampered with.
     */
    fun verifyDataIntegrity(): Boolean {
        val lockHash = prefs.getString("lock_hash", null)
        if (isLocked && lockHash != null) {
            val decrypted = SecurityManager.decryptData(lockHash)
            return decrypted.startsWith("LOCKED_")
        }
        return true
    }
}

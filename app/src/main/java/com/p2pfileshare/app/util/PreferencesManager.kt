package com.p2pfileshare.app.util

import android.content.Context
import android.content.SharedPreferences

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
}

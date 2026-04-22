package com.p2pfileshare.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.p2pfileshare.app.service.P2PService
import com.p2pfileshare.app.util.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = PreferencesManager(context)
                if (prefs.isAutoStart) {
                    P2PService.start(context)
                }
            }
        }
    }
}

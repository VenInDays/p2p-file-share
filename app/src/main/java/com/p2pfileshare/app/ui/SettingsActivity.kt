package com.p2pfileshare.app.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.remote.ScreenCaptureManager
import com.p2pfileshare.app.remote.RemoteGestureService
import com.p2pfileshare.app.service.P2PService
import com.p2pfileshare.app.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var switchLock: SwitchMaterial
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var switchRemoteControl: SwitchMaterial
    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvPortInfo: TextView
    private lateinit var tvScreenCaptureStatus: TextView
    private lateinit var tvGestureStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferencesManager(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cài đặt"

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Refresh IP every time settings is shown
        updateIpInfo()
        // Update remote control status
        updateRemoteStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == ScreenCaptureManager.REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Got MediaProjection permission
                val projectionManager = ScreenCaptureManager.getProjectionManager(this)
                if (projectionManager != null) {
                    val projection = projectionManager.getMediaProjection(resultCode, data)
                    if (projection != null) {
                        val captureManager = ScreenCaptureManager.create()
                        captureManager.setMediaProjection(projection)
                        val started = captureManager.startCapture(this)
                        if (started) {
                            prefs.isRemoteControlEnabled = true
                            switchRemoteControl.isChecked = true
                            Toast.makeText(this, "Screen Capture đã bật!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Không thể bắt đầu Screen Capture", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                updateRemoteStatus()
            } else {
                Toast.makeText(this, "Quyền Screen Capture bị từ chối", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initViews() {
        switchLock = findViewById(R.id.switchLock)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchRemoteControl = findViewById(R.id.switchRemoteControl)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvPortInfo = findViewById(R.id.tvPortInfo)
        tvScreenCaptureStatus = findViewById(R.id.tvScreenCaptureStatus)
        tvGestureStatus = findViewById(R.id.tvGestureStatus)

        switchLock.isChecked = prefs.isLocked
        switchAutoStart.isChecked = prefs.isAutoStart
        switchRemoteControl.isChecked = prefs.isRemoteControlEnabled
        tvDeviceName.text = prefs.serviceName

        updateIpInfo()
        updateRemoteStatus()
    }

    private fun updateIpInfo() {
        val ip = App.getWifiIpAddress()
        val port = P2PService.getServerPort().takeIf { it > 0 } ?: prefs.serverPort

        tvIpAddress.text = ip
        tvPortInfo.text = "Port: $port"

        if (ip == "Unknown") {
            tvIpAddress.text = "Không có WiFi"
            tvPortInfo.text = "Hãy kết nối WiFi trước"
        }
    }

    private fun updateRemoteStatus() {
        val captureActive = ScreenCaptureManager.instance?.isCapturing() == true
        val gestureActive = RemoteGestureService.instance != null

        tvScreenCaptureStatus.text = if (captureActive) "Screen Capture: BẬT" else "Screen Capture: TẮT"
        tvScreenCaptureStatus.setTextColor(
            if (captureActive) getColor(android.R.color.holo_green_dark) else getColor(android.R.color.holo_red_dark)
        )

        tvGestureStatus.text = if (gestureActive) "Accessibility Service: BẬT" else "Accessibility Service: TẮT"
        tvGestureStatus.setTextColor(
            if (gestureActive) getColor(android.R.color.holo_green_dark) else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun setupListeners() {
        switchLock.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isLocked = isChecked
            val msg = if (isChecked) {
                "Đã khóa! Thiết bị khác không thể truy cập máy này, nhưng bạn vẫn có thể truy cập máy khác."
            } else {
                "Đã mở khóa. Thiết bị khác có thể truy cập máy bạn."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        switchAutoStart.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isAutoStart = isChecked
        }

        switchRemoteControl.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                requestScreenCapturePermission()
            } else {
                ScreenCaptureManager.instance?.release()
                prefs.isRemoteControlEnabled = false
                updateRemoteStatus()
            }
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardScreenCapture).setOnClickListener {
            if (ScreenCaptureManager.instance?.isCapturing() != true) {
                requestScreenCapturePermission()
            } else {
                // Already capturing, show info
                Toast.makeText(this, "Screen Capture đang bật", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardGestureService).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDeviceName).setOnClickListener {
            showDeviceNameDialog()
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardRestartService).setOnClickListener {
            P2PService.stop(this)
            P2PService.start(this)
            Toast.makeText(this, "Đã khởi động lại dịch vụ", Toast.LENGTH_SHORT).show()
            // Refresh IP after restart
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateIpInfo()
            }, 2000)
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val projectionManager = ScreenCaptureManager.getProjectionManager(this)
            if (projectionManager != null) {
                val intent = projectionManager.createScreenCaptureIntent()
                @Suppress("DEPRECATION")
                startActivityForResult(intent, ScreenCaptureManager.REQUEST_CODE)
            } else {
                Toast.makeText(this, "Không thể khởi tạo Screen Capture", Toast.LENGTH_SHORT).show()
                switchRemoteControl.isChecked = false
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            switchRemoteControl.isChecked = false
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Tìm 'P2P File Share' và bật lên", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở Accessibility Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeviceNameDialog() {
        val input = EditText(this).apply {
            setText(prefs.serviceName)
            setSelection(prefs.serviceName.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Tên thiết bị")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.serviceName = name
                    tvDeviceName.text = name
                    // Restart service to update NSD registration
                    P2PService.stop(this)
                    P2PService.start(this)
                    Toast.makeText(this, "Đã cập nhật tên thiết bị", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}

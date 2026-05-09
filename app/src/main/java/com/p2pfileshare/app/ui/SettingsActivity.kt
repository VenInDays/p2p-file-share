package com.p2pfileshare.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.admin.DeviceAdminManager
import com.p2pfileshare.app.security.SecurityManager
import com.p2pfileshare.app.service.P2PService
import com.p2pfileshare.app.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var switchLock: SwitchMaterial
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var switchSecurity: SwitchMaterial
    private lateinit var switchRateLimit: SwitchMaterial
    private lateinit var switchEncryption: SwitchMaterial
    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvPortInfo: TextView
    private lateinit var tvApiToken: TextView

    // Device Admin views
    private lateinit var tvDeviceAdminStatus: TextView
    private lateinit var btnActivateDeviceAdmin: MaterialButton
    private lateinit var layoutDeviceOwner: LinearLayout
    private lateinit var tvDeviceOwnerSteps: TextView
    private lateinit var btnCopyAdbCommand: MaterialButton

    private val DEVICE_ADMIN_REQUEST_CODE = 2001

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
        updateIpInfo()
        updateDeviceAdminStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DEVICE_ADMIN_REQUEST_CODE) {
            updateDeviceAdminStatus()
            if (DeviceAdminManager.isAdminActive(this)) {
                Toast.makeText(this, "Device Admin đã bật! App khó bị gỡ hơn.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initViews() {
        switchLock = findViewById(R.id.switchLock)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvPortInfo = findViewById(R.id.tvPortInfo)

        switchLock.isChecked = prefs.isLocked
        switchAutoStart.isChecked = prefs.isAutoStart
        tvDeviceName.text = prefs.serviceName

        // Security switches - created programmatically (not in XML layout)
        switchSecurity = createSwitch("Bảo mật API", "Chỉ thiết bị có app mới gọi được API")
        switchRateLimit = createSwitch("Rate Limiting", "Chống brute-force (60 req/phút)")
        switchEncryption = createSwitch("Mã hóa dữ liệu", "AES-256-GCM cho dữ liệu nhạy cảm")

        switchSecurity.isChecked = prefs.isSecurityEnabled
        switchRateLimit.isChecked = prefs.isRateLimitEnabled
        switchEncryption.isChecked = prefs.isEncryptionEnabled

        // API Token display - created programmatically
        tvApiToken = TextView(this).apply {
            text = "API Token: ${SecurityManager.getApiToken().take(12)}..."
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }

        // Add programmatically created views to the layout
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val rootLayout = tvVersion.parent as? LinearLayout
        if (rootLayout != null) {
            val versionIndex = rootLayout.indexOfChild(tvVersion)
            // Insert switches and token before the version text
            if (versionIndex >= 0) {
                rootLayout.addView(tvApiToken, versionIndex)
                rootLayout.addView(switchEncryption, versionIndex)
                rootLayout.addView(switchRateLimit, versionIndex)
                rootLayout.addView(switchSecurity, versionIndex)
            } else {
                rootLayout.addView(switchSecurity)
                rootLayout.addView(switchRateLimit)
                rootLayout.addView(switchEncryption)
                rootLayout.addView(tvApiToken)
            }
        }

        // Device Admin views
        tvDeviceAdminStatus = findViewById(R.id.tvDeviceAdminStatus)
        btnActivateDeviceAdmin = findViewById(R.id.btnActivateDeviceAdmin)
        layoutDeviceOwner = findViewById(R.id.layoutDeviceOwner)
        tvDeviceOwnerSteps = findViewById(R.id.tvDeviceOwnerSteps)
        btnCopyAdbCommand = findViewById(R.id.btnCopyAdbCommand)

        updateIpInfo()
        updateDeviceAdminStatus()
    }

    private fun createSwitch(label: String, description: String): SwitchMaterial {
        return SwitchMaterial(this).apply {
            text = label
            hint = description
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
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

    private fun updateDeviceAdminStatus() {
        val isAdmin = DeviceAdminManager.isAdminActive(this)
        val isOwner = DeviceAdminManager.isDeviceOwner(this)
        val isProfileOwner = DeviceAdminManager.isProfileOwner(this)

        // Update status text
        tvDeviceAdminStatus.text = when {
            isOwner -> {
                tvDeviceAdminStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                "DEVICE OWNER - Ứng dụng hệ thống\nApp KHÔNG THỂ bị gỡ cài đặt!"
            }
            isProfileOwner -> {
                tvDeviceAdminStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                "PROFILE OWNER\nApp quản lý hồ sơ công việc"
            }
            isAdmin -> {
                tvDeviceAdminStatus.setTextColor(getColor(android.R.color.holo_blue_dark))
                "DEVICE ADMIN\nApp khó bị gỡ hơn (phải tắt Admin trước)"
            }
            else -> {
                tvDeviceAdminStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                "Chưa bật\nBật Device Admin để bảo vệ app"
            }
        }

        // Update button
        when {
            isOwner -> {
                btnActivateDeviceAdmin.text = "Đã là Device Owner"
                btnActivateDeviceAdmin.isEnabled = false
                layoutDeviceOwner.visibility = View.VISIBLE
            }
            isAdmin -> {
                btnActivateDeviceAdmin.text = "Tắt Device Admin"
                btnActivateDeviceAdmin.isEnabled = true
                layoutDeviceOwner.visibility = View.VISIBLE
            }
            else -> {
                btnActivateDeviceAdmin.text = "Bật Device Admin"
                btnActivateDeviceAdmin.isEnabled = true
                layoutDeviceOwner.visibility = View.GONE
            }
        }

        // Update Device Owner steps
        val adbCommand = DeviceAdminManager.getDeviceOwnerAdbCommand(this)
        val removeCommand = DeviceAdminManager.getRemoveDeviceOwnerAdbCommand(this)
        tvDeviceOwnerSteps.text = buildString {
            append("Hướng dẫn set Device Owner:\n\n")
            append("Bước 1: Xóa tất cả tài khoản trên máy\n")
            append("  (Settings > Accounts > Remove all)\n\n")
            append("Bước 2: Kết nối máy tính qua USB\n\n")
            append("Bước 3: Chạy lệnh ADB:\n")
            append("  $adbCommand\n\n")
            append("Để gỡ Device Owner sau này:\n")
            append("  $removeCommand")
        }
    }

    private fun setupListeners() {
        switchLock.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isLocked = isChecked

            if (isChecked) {
                // When locking, encrypt sensitive data
                prefs.lockSecureData()
                val msg = "Đã khóa! Thiết bị khác không thể truy cập máy này.\n" +
                        "- API token yêu cầu cho mọi request\n" +
                        "- Dữ liệu nhạy cảm đã được mã hóa AES-256\n" +
                        "- Terminal/external access bị chặn\n" +
                        "- Rate limiting chống brute-force"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                val msg = "Đã mở khóa. Thiết bị khác có thể truy cập máy bạn."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isAutoStart = isChecked
        }

        switchSecurity.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isSecurityEnabled = isChecked
            val msg = if (isChecked) "Bảo mật API đã bật. Chỉ thiết bị có token mới gọi được API."
            else "Bảo mật API đã tắt. Bất kỳ ai cũng có thể gọi API."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        switchRateLimit.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isRateLimitEnabled = isChecked
            val msg = if (isChecked) "Rate limiting đã bật. Chống brute-force."
            else "Rate limiting đã tắt."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        switchEncryption.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.isEncryptionEnabled = isChecked
            val msg = if (isChecked) "Mã hóa AES-256-GCM đã bật. Dữ liệu được bảo vệ."
            else "Mã hóa đã tắt."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Device Admin button
        btnActivateDeviceAdmin.setOnClickListener {
            if (DeviceAdminManager.isDeviceOwner(this)) {
                Toast.makeText(this, "App đã là Device Owner - không thể tắt từ đây", Toast.LENGTH_SHORT).show()
            } else if (DeviceAdminManager.isAdminActive(this)) {
                // Show confirmation before deactivating
                AlertDialog.Builder(this)
                    .setTitle("Tắt Device Admin?")
                    .setMessage("Nếu tắt Device Admin, app sẽ dễ bị gỡ cài đặt hơn. Bạn có chắc muốn tắt?")
                    .setPositiveButton("Tắt") { _, _ ->
                        DeviceAdminManager.deactivateDeviceAdmin(this)
                        updateDeviceAdminStatus()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                // Activate device admin
                DeviceAdminManager.activateDeviceAdmin(this)
            }
        }

        // Copy ADB command button
        btnCopyAdbCommand.setOnClickListener {
            val adbCommand = DeviceAdminManager.getDeviceOwnerAdbCommand(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
            Toast.makeText(this, "Đã sao chép lệnh ADB!", Toast.LENGTH_SHORT).show()
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDeviceName)?.setOnClickListener {
            showDeviceNameDialog()
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardRestartService)?.setOnClickListener {
            P2PService.stop(this)
            P2PService.start(this)
            Toast.makeText(this, "Đã khởi động lại dịch vụ", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateIpInfo()
            }, 2000)
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
                    P2PService.stop(this)
                    P2PService.start(this)
                    Toast.makeText(this, "Đã cập nhật tên thiết bị", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}

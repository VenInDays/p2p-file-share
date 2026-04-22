package com.p2pfileshare.app.ui

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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

        updateIpInfo()
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

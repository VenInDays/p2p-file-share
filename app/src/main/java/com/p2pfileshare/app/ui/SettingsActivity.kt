package com.p2pfileshare.app.ui

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.p2pfileshare.app.R
import com.p2pfileshare.app.service.P2PService
import com.p2pfileshare.app.util.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var switchLock: SwitchMaterial
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var tvDeviceName: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferencesManager(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cài đặt"

        initViews()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initViews() {
        switchLock = findViewById(R.id.switchLock)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        tvDeviceName = findViewById(R.id.tvDeviceName)

        switchLock.isChecked = prefs.isLocked
        switchAutoStart.isChecked = prefs.isAutoStart
        tvDeviceName.text = prefs.serviceName
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

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDeviceName).setOnClickListener {
            showDeviceNameDialog()
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardRestartService).setOnClickListener {
            P2PService.stop(this)
            P2PService.start(this)
            Toast.makeText(this, "Đã khởi động lại dịch vụ", Toast.LENGTH_SHORT).show()
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

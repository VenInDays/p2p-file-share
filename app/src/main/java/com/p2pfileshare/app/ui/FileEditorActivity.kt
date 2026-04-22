package com.p2pfileshare.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.util.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileEditorActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var editText: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var tvEditorFileName: TextView
    private lateinit var tvEditorStatus: TextView
    private lateinit var tvCharCount: TextView
    private lateinit var progressBar: ProgressBar

    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var filePath: String = ""
    private var fileName: String = ""
    private var isSaving = false
    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        apiClient = ApiClient()

        // Setup toolbar manually (theme is NoActionBar)
        val toolbar = findViewById<Toolbar>(R.id.editorToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        toolbar.setNavigationOnClickListener { handleBack() }

        // Bind views
        editText = findViewById(R.id.etFileContent)
        btnSave = findViewById(R.id.btnSave)
        tvEditorFileName = findViewById(R.id.tvEditorFileName)
        tvEditorStatus = findViewById(R.id.tvEditorStatus)
        tvCharCount = findViewById(R.id.tvCharCount)
        progressBar = findViewById(R.id.progressBar)

        // Get intent data
        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        filePath = intent.getStringExtra("file_path") ?: ""
        fileName = intent.getStringExtra("file_name") ?: "Untitled"

        // Setup UI
        tvEditorFileName.text = fileName
        tvEditorStatus.text = "Chỉnh sửa"

        // Load content
        val content = intent.getStringExtra("file_content") ?: ""
        editText.setText(content)
        updateCharCount()

        // Enable EditText scrolling without ScrollView (fixes lag)
        editText.setSingleLine(false)
        editText.maxLines = Integer.MAX_VALUE

        // Track changes
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                hasUnsavedChanges = true
                tvEditorStatus.text = "Chưa lưu *"
                tvEditorStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                updateCharCount()
            }
        })

        // Save button always visible
        btnSave.setOnClickListener {
            if (!isSaving) saveFile()
        }
    }

    private fun updateCharCount() {
        val count = editText.text.length
        val lines = editText.lineCount
        tvCharCount.text = "$count ký tự · $lines dòng"
    }

    private fun saveFile() {
        if (isSaving) return
        isSaving = true
        btnSave.isEnabled = false
        btnSave.text = "ĐANG LƯU..."
        tvEditorStatus.text = "Đang lưu..."

        val content = editText.text.toString()
        val peer = PeerDevice("", peerHost, peerPort)

        lifecycleScope.launch {
            val success = apiClient.editFile(peer, filePath, content)
            withContext(Dispatchers.Main) {
                isSaving = false
                btnSave.isEnabled = true
                btnSave.text = "LƯU"
                if (success) {
                    hasUnsavedChanges = false
                    tvEditorStatus.text = "Đã lưu ✓"
                    tvEditorStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    Toast.makeText(this@FileEditorActivity, "Đã lưu", Toast.LENGTH_SHORT).show()
                } else {
                    tvEditorStatus.text = "Lưu thất bại!"
                    tvEditorStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    Toast.makeText(this@FileEditorActivity, "Lưu thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleBack() {
        if (hasUnsavedChanges) {
            // Show confirm dialog
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Chưa lưu")
                .setMessage("Bạn có thay đổi chưa lưu. Lưu trước khi thoát?")
                .setPositiveButton("Lưu") { _, _ ->
                    saveFile()
                    finish()
                }
                .setNegativeButton("Không lưu") { _, _ ->
                    finish()
                }
                .setNeutralButton("Hủy", null)
                .show()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    override fun onSupportNavigateUp(): Boolean {
        handleBack()
        return true
    }
}

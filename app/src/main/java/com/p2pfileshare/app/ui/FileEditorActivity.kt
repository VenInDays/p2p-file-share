package com.p2pfileshare.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.p2pfileshare.app.R
import com.p2pfileshare.app.util.ApiClient
import com.p2pfileshare.app.model.PeerDevice
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileEditorActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var editText: EditText

    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var filePath: String = ""
    private var fileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        apiClient = ApiClient()
        editText = findViewById(R.id.etFileContent)

        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        filePath = intent.getStringExtra("file_path") ?: ""
        fileName = intent.getStringExtra("file_name") ?: "Untitled"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = fileName
        supportActionBar?.subtitle = "Chỉnh sửa"

        val content = intent.getStringExtra("file_content") ?: ""
        editText.setText(content)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveFile()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveFile() {
        val content = editText.text.toString()
        val peer = PeerDevice("", peerHost, peerPort)

        lifecycleScope.launch {
            val success = apiClient.editFile(peer, filePath, content)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@FileEditorActivity, "Đã lưu", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FileEditorActivity, "Lưu thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

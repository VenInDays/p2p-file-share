package com.p2pfileshare.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.AppInfo
import com.p2pfileshare.app.model.FileItem
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.service.P2PService
import com.p2pfileshare.app.util.ApiClient
import com.p2pfileshare.app.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var apiClient: ApiClient

    private lateinit var tvStatus: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAdd: FloatingActionButton

    private lateinit var peerAdapter: PeerAdapter
    private lateinit var fileAdapter: FileListAdapter

    private var currentPeer: PeerDevice? = null
    private var currentPath: String = "/"
    private var pathHistory = mutableListOf<String>()
    private var isBrowsingFiles = false

    private val UPLOAD_REQUEST_CODE = 1001
    private val AUDIO_PICK_REQUEST_CODE = 1002
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            prefs = PreferencesManager(this)
            apiClient = ApiClient()

            initViews()
            setupRecyclerView()
            setupListeners()
            checkAndRequestPermissions()
        } catch (e: Exception) {
            LogHelper.e("MainActivity", "onCreate failed", e)
            Toast.makeText(this, "Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            P2PService.setPeerCallbacks(
                onDiscovered = { peer -> runOnUiThread { peerAdapter.addPeer(peer) } },
                onLost = { name -> runOnUiThread { peerAdapter.removePeer(name) } }
            )

            val existingPeers = P2PService.getDiscoveredPeers()
            if (existingPeers.isNotEmpty()) {
                peerAdapter.setPeers(existingPeers)
            }
        } catch (e: Exception) {
            LogHelper.e("MainActivity", "onResume failed", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh_peers -> {
                refreshPeers()
                true
            }
            R.id.action_manual_connect -> {
                showManualConnectDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Use onActivityResult callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPLOAD_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val peer = currentPeer ?: return
            val uri = data.data ?: return
            uploadUriToPeer(peer, uri)
        } else if (requestCode == AUDIO_PICK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val peer = currentPeer ?: return
            val uri = data.data ?: return
            playAudioOnPeer(peer, uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startP2PService()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        recyclerView = findViewById(R.id.recyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        fabAdd = findViewById(R.id.fabAdd)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "P2P File Share"

        tvStatus.text = "Đang kiểm tra quyền..."
    }

    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter(this) { peer -> showPeerOptionsDialog(peer) }
        fileAdapter = FileListAdapter(this,
            onItemClick = { file -> onFileItemClick(file) },
            onItemLongClick = { file -> onFileItemLongClick(file) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = peerAdapter
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            if (isBrowsingFiles && currentPeer != null) {
                browsePath(currentPath)
            } else {
                refreshPeers()
            }
            swipeRefresh.isRefreshing = false
        }

        fabAdd.setOnClickListener {
            if (isBrowsingFiles && currentPeer != null) {
                showCreateDialog()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startP2PService()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } catch (e: Exception) {
                LogHelper.e("MainActivity", "Failed to request all files access", e)
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (e2: Exception) {
                    LogHelper.e("MainActivity", "Failed to open all files access settings", e2)
                }
            }
        }
    }

    private fun startP2PService() {
        try {
            if (!P2PService.isRunning) {
                P2PService.start(this)
            }
            tvStatus.text = "Đang tìm thiết bị cùng WiFi..."
        } catch (e: Exception) {
            LogHelper.e("MainActivity", "Failed to start P2P service", e)
            tvStatus.text = "Lỗi khởi động dịch vụ. Thử lại trong Settings."
        }
    }

    private fun refreshPeers() {
        val peers = P2PService.getDiscoveredPeers()
        peerAdapter.setPeers(peers)
        if (peers.isEmpty()) {
            tvEmptyState.text = "Chưa tìm thấy thiết bị nào.\nĐảm bảo 2 máy cùng WiFi và đã mở app."
            tvEmptyState.visibility = View.VISIBLE
        } else {
            tvEmptyState.visibility = View.GONE
        }
    }

    // ============ Peer Options Dialog ============

    private fun showPeerOptionsDialog(peer: PeerDevice) {
        val options = arrayOf(
            "Duyệt file",
            "Quản lý ứng dụng",
            "Điều khiển WiFi",
            "Phát âm thanh"
        )

        AlertDialog.Builder(this)
            .setTitle(peer.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> connectToPeer(peer)
                    1 -> showAppManagementDialog(peer)
                    2 -> showWifiControlDialog(peer)
                    3 -> showPlayAudioDialog(peer)
                }
            }
            .show()
    }

    // ============ File Browsing ============

    private fun connectToPeer(peer: PeerDevice) {
        currentPeer = peer
        currentPath = "/"
        pathHistory.clear()
        isBrowsingFiles = true

        supportActionBar?.title = peer.name
        recyclerView.adapter = fileAdapter
        fabAdd.show()

        browsePath("/")
    }

    private fun browsePath(path: String) {
        val peer = currentPeer ?: return
        showLoading(true)

        lifecycleScope.launch {
            try {
                val dirInfo = apiClient.listFiles(peer, path)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (dirInfo != null) {
                        currentPath = path
                        tvStatus.text = path
                        fileAdapter.setItems(dirInfo.files)
                        tvEmptyState.visibility = if (dirInfo.files.isEmpty()) View.VISIBLE else View.GONE
                        if (dirInfo.files.isEmpty()) {
                            tvEmptyState.text = "Thư mục trống"
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Không thể kết nối", Toast.LENGTH_SHORT).show()
                        showPeerList()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onFileItemClick(file: FileItem) {
        if (file.isDirectory) {
            pathHistory.add(currentPath)
            browsePath(file.path)
        } else {
            showFileOptionsDialog(file)
        }
    }

    private fun onFileItemLongClick(file: FileItem) {
        showFileOptionsDialog(file)
    }

    private fun showFileOptionsDialog(file: FileItem) {
        val options = if (file.isDirectory) {
            arrayOf("Tải xuống (ZIP)", "Đổi tên", "Xóa")
        } else {
            val isTextFile = file.name.endsWith(".txt") || file.name.endsWith(".log") ||
                    file.name.endsWith(".md") || file.name.endsWith(".json") ||
                    file.name.endsWith(".xml") || file.name.endsWith(".html") ||
                    file.name.endsWith(".css") || file.name.endsWith(".js") ||
                    file.name.endsWith(".csv") || file.name.endsWith(".properties")
            val isAudioFile = file.name.endsWith(".mp3") || file.name.endsWith(".wav") ||
                    file.name.endsWith(".ogg") || file.name.endsWith(".flac") ||
                    file.name.endsWith(".m4a") || file.name.endsWith(".aac")

            when {
                isTextFile && isAudioFile -> arrayOf("Tải xuống", "Chỉnh sửa", "Phát trên máy kia", "Đổi tên", "Xóa")
                isTextFile -> arrayOf("Tải xuống", "Chỉnh sửa", "Đổi tên", "Xóa")
                isAudioFile -> arrayOf("Tải xuống", "Phát trên máy kia", "Đổi tên", "Xóa")
                else -> arrayOf("Tải xuống", "Đổi tên", "Xóa")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Tải xuống" -> downloadFile(file)
                    "Tải xuống (ZIP)" -> downloadFile(file)
                    "Chỉnh sửa" -> editFile(file)
                    "Phát trên máy kia" -> playAudioFileOnPeer(file)
                    "Đổi tên" -> showRenameDialog(file)
                    "Xóa" -> showDeleteConfirmDialog(file)
                }
            }
            .show()
    }

    private fun playAudioFileOnPeer(file: FileItem) {
        val peer = currentPeer ?: return
        showLoading(true)

        lifecycleScope.launch {
            try {
                // First download the file locally
                val tempDir = File(cacheDir, "p2p_audio_send")
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, file.name)

                val downloaded = apiClient.downloadFile(peer, file.path, tempDir)
                if (!downloaded) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(this@MainActivity, "Không thể tải file âm thanh", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Then send it to play on the remote device
                val response = apiClient.playAudio(peer, tempFile)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (response.success) {
                        Toast.makeText(this@MainActivity, "Đang phát âm thanh trên ${peer.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadFile(file: FileItem) {
        val peer = currentPeer ?: return
        val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        showLoading(true)
        lifecycleScope.launch {
            val success = apiClient.downloadFile(peer, file.path, destDir)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã tải xuống: ${file.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Tải xuống thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun editFile(file: FileItem) {
        val peer = currentPeer ?: return
        showLoading(true)

        lifecycleScope.launch {
            val content = apiClient.getFileContent(peer, file.path)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (content != null) {
                    val intent = Intent(this@MainActivity, FileEditorActivity::class.java)
                    intent.putExtra("peer_host", peer.host)
                    intent.putExtra("peer_port", peer.port)
                    intent.putExtra("file_path", file.path)
                    intent.putExtra("file_name", file.name)
                    intent.putExtra("file_content", content)
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Không thể đọc file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRenameDialog(file: FileItem) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(file.name.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Đổi tên")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    renameFile(file, newName)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun renameFile(file: FileItem, newName: String) {
        val peer = currentPeer ?: return
        lifecycleScope.launch {
            val success = apiClient.renameFile(peer, file.path, newName)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã đổi tên", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Đổi tên thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(file: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận xóa")
            .setMessage("Bạn có chắc muốn xóa \"${file.name}\" không?")
            .setPositiveButton("Xóa") { _, _ -> deleteFile(file) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun deleteFile(file: FileItem) {
        val peer = currentPeer ?: return
        lifecycleScope.launch {
            val success = apiClient.deleteFile(peer, file.path)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã xóa", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Xóa thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateDialog() {
        val options = arrayOf("Tạo thư mục", "Tạo file văn bản", "Tải file lên")
        AlertDialog.Builder(this)
            .setTitle("Tạo mới")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateFolderDialog()
                    1 -> showCreateFileDialog()
                    2 -> openFilePicker()
                }
            }
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "Tên thư mục"
        }

        AlertDialog.Builder(this)
            .setTitle("Tạo thư mục mới")
            .setView(input)
            .setPositiveButton("Tạo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createFolder(name)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showCreateFileDialog() {
        val input = EditText(this).apply {
            hint = "Tên file (ví dụ: note.txt)"
        }

        AlertDialog.Builder(this)
            .setTitle("Tạo file mới")
            .setView(input)
            .setPositiveButton("Tạo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createFile(name)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun createFolder(name: String) {
        val peer = currentPeer ?: return
        lifecycleScope.launch {
            val success = apiClient.createFolder(peer, currentPath, name)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã tạo thư mục", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Tạo thư mục thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createFile(name: String) {
        val peer = currentPeer ?: return
        lifecycleScope.launch {
            val success = apiClient.createFile(peer, currentPath, name)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã tạo file", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Tạo file thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Chọn file để tải lên"), UPLOAD_REQUEST_CODE)
    }

    private fun uploadUriToPeer(peer: PeerDevice, uri: Uri) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                val tempFile = File(cacheDir, "upload_temp_${System.currentTimeMillis()}")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val success = apiClient.uploadFile(peer, tempFile, currentPath)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (success) {
                        Toast.makeText(this@MainActivity, "Đã tải lên", Toast.LENGTH_SHORT).show()
                        browsePath(currentPath)
                    } else {
                        Toast.makeText(this@MainActivity, "Tải lên thất bại", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ============ App Management ============

    private fun showAppManagementDialog(peer: PeerDevice) {
        currentPeer = peer
        showLoading(true)

        lifecycleScope.launch {
            try {
                val apps = apiClient.listApps(peer, "all")
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (apps != null) {
                        showAppListDialog(peer, apps)
                    } else {
                        Toast.makeText(this@MainActivity, "Không thể lấy danh sách ứng dụng", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAppListDialog(peer: PeerDevice, apps: List<AppInfo>) {
        val appNames = apps.map { app ->
            val prefix = if (app.isSystemApp) "[Hệ thống] " else ""
            val suffix = if (!app.isEnabled) " (Đã tắt)" else ""
            "$prefix${app.name}$suffix"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Quản lý ứng dụng - ${peer.name}\n(${apps.size} ứng dụng)")
            .setItems(appNames) { _, which ->
                val app = apps[which]
                showAppActionsDialog(peer, app)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showAppActionsDialog(peer: PeerDevice, app: AppInfo) {
        val options = mutableListOf<String>()

        if (app.hasLaunchIntent) {
            options.add("Mở ứng dụng")
        }
        options.add("Thoát ứng dụng")
        options.add("Gỡ cài đặt")

        if (app.isEnabled) {
            options.add("Tắt ứng dụng (giới hạn)")
        } else {
            options.add("Bật ứng dụng")
        }

        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Mở ứng dụng" -> launchAppOnPeer(peer, app)
                    "Thoát ứng dụng" -> forceStopAppOnPeer(peer, app)
                    "Gỡ cài đặt" -> uninstallAppOnPeer(peer, app)
                    "Tắt ứng dụng (giới hạn)" -> restrictAppOnPeer(peer, app, enable = false)
                    "Bật ứng dụng" -> restrictAppOnPeer(peer, app, enable = true)
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun launchAppOnPeer(peer: PeerDevice, app: AppInfo) {
        showLoading(true)
        lifecycleScope.launch {
            val response = apiClient.launchApp(peer, app.packageName)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (response.success) {
                    Toast.makeText(this@MainActivity, "Đã mở ${app.name} trên ${peer.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun forceStopAppOnPeer(peer: PeerDevice, app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận")
            .setMessage("Thoát ${app.name} trên ${peer.name}?")
            .setPositiveButton("Thoát") { _, _ ->
                showLoading(true)
                lifecycleScope.launch {
                    val response = apiClient.forceStopApp(peer, app.packageName)
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        if (response.success) {
                            Toast.makeText(this@MainActivity, "Đã thoát ${app.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun uninstallAppOnPeer(peer: PeerDevice, app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận gỡ cài đặt")
            .setMessage("Gỡ cài đặt ${app.name} trên ${peer.name}?")
            .setPositiveButton("Gỡ") { _, _ ->
                showLoading(true)
                lifecycleScope.launch {
                    val response = apiClient.uninstallApp(peer, app.packageName)
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        if (response.success) {
                            Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun restrictAppOnPeer(peer: PeerDevice, app: AppInfo, enable: Boolean) {
        showLoading(true)
        lifecycleScope.launch {
            val response = apiClient.restrictAppWifi(peer, app.packageName, enable)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (response.success) {
                    Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============ WiFi Control ============

    private fun showWifiControlDialog(peer: PeerDevice) {
        val options = arrayOf("Bật WiFi", "Tắt WiFi")

        AlertDialog.Builder(this)
            .setTitle("Điều khiển WiFi - ${peer.name}")
            .setItems(options) { _, which ->
                val action = when (which) {
                    0 -> "enable"
                    1 -> "disable"
                    else -> return@setItems
                }
                controlWifiOnPeer(peer, action)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun controlWifiOnPeer(peer: PeerDevice, action: String) {
        showLoading(true)
        lifecycleScope.launch {
            val response = apiClient.controlWifi(peer, action)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (response.success) {
                    Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============ Play Audio ============

    private fun showPlayAudioDialog(peer: PeerDevice) {
        val options = arrayOf("Chọn file âm thanh từ máy này", "Chọn file âm thanh từ máy kia", "Dừng phát âm thanh")

        AlertDialog.Builder(this)
            .setTitle("Phát âm thanh - ${peer.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickAudioFileForPeer(peer)
                    1 -> browseAudioFilesOnPeer(peer)
                    2 -> stopAudioOnPeer(peer)
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun pickAudioFileForPeer(peer: PeerDevice) {
        currentPeer = peer
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Chọn file âm thanh"), AUDIO_PICK_REQUEST_CODE)
    }

    private fun playAudioOnPeer(peer: PeerDevice, uri: Uri) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                val tempFile = File(cacheDir, "audio_send_${System.currentTimeMillis()}")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val response = apiClient.playAudio(peer, tempFile)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (response.success) {
                        Toast.makeText(this@MainActivity, "Đang phát âm thanh trên ${peer.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun browseAudioFilesOnPeer(peer: PeerDevice) {
        // Connect to peer and let user browse files, then play audio
        currentPeer = peer
        connectToPeer(peer)
        Toast.makeText(this, "Chọn file âm thanh từ máy kia (mp3, wav, ogg, flac, m4a)", Toast.LENGTH_LONG).show()
    }

    private fun stopAudioOnPeer(peer: PeerDevice) {
        showLoading(true)
        lifecycleScope.launch {
            val response = apiClient.stopAudio(peer)
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (response.success) {
                    Toast.makeText(this@MainActivity, "Đã dừng âm thanh trên ${peer.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Lỗi: ${response.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ============ Navigation ============

    private fun showManualConnectDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val hostInput = EditText(this).apply {
            hint = "IP Address (ví dụ: 192.168.1.100)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(this).apply {
            hint = "Port (mặc định: 9527)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("9527")
        }

        layout.addView(hostInput)
        layout.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle("Kết nối thủ công")
            .setView(layout)
            .setPositiveButton("Kết nối") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 9527
                if (host.isNotEmpty()) {
                    val peer = PeerDevice("Manual ($host)", host, port)
                    showPeerOptionsDialog(peer)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showPeerList() {
        isBrowsingFiles = false
        currentPeer = null
        currentPath = "/"
        pathHistory.clear()

        supportActionBar?.title = "P2P File Share"
        recyclerView.adapter = peerAdapter
        fabAdd.hide()
        tvStatus.text = "Đang tìm thiết bị cùng WiFi..."
        tvEmptyState.text = "Chưa tìm thấy thiết bị nào.\nĐảm bảo 2 máy cùng WiFi và đã mở app."
        tvEmptyState.visibility = if (peerAdapter.itemCount == 0) View.VISIBLE else View.GONE

        refreshPeers()
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isBrowsingFiles && pathHistory.isNotEmpty()) {
            pathHistory.removeAt(pathHistory.size - 1)
            if (pathHistory.isEmpty()) {
                showPeerList()
            } else {
                currentPath = pathHistory.last()
                browsePath(currentPath)
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

/**
 * Simple log helper to avoid crashes from logging
 */
private object LogHelper {
    fun e(tag: String, msg: String, e: Exception) {
        try {
            android.util.Log.e(tag, msg, e)
        } catch (_: Exception) {}
    }
}

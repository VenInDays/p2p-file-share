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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
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
        peerAdapter = PeerAdapter(this) { peer -> connectToPeer(peer) }
        fileAdapter = FileListAdapter(this,
            onItemClick = { file -> onFileItemClick(file) },
            onItemLongClick = { file -> showFileOptionsModal(file) }
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
                showCreateModal()
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

    private fun connectToPeer(peer: PeerDevice) {
        currentPeer = peer
        currentPath = "/"
        pathHistory.clear()
        isBrowsingFiles = true

        fileAdapter.currentPeer = peer
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

    // ===== File click: tap to preview, long-press for options modal =====

    private fun onFileItemClick(file: FileItem) {
        if (file.isDirectory) {
            // Enter directory
            pathHistory.add(currentPath)
            browsePath(file.path)
        } else if (FilePreviewActivity.isImageFile(file.name) || FilePreviewActivity.isVideoFile(file.name)) {
            // Preview image/video directly
            previewFile(file)
        } else {
            // All other files (text, unknown extension, no extension) → open editor
            editFile(file)
        }
    }

    private fun previewFile(file: FileItem) {
        val peer = currentPeer ?: return
        val intent = Intent(this, FilePreviewActivity::class.java)
        intent.putExtra("peer_host", peer.host)
        intent.putExtra("peer_port", peer.port)
        intent.putExtra("file_path", file.path)
        intent.putExtra("file_name", file.name)
        intent.putExtra("file_size", file.size)
        intent.putExtra("file_mime", file.mimeType)
        startActivity(intent)
    }

    // ===== Bottom Sheet Modal (replaces AlertDialog) =====

    private fun showFileOptionsModal(file: FileItem) {
        val bottomSheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.modal_file_options, null)
        bottomSheet.setContentView(view)

        val modalIcon: ImageView = view.findViewById(R.id.modalIcon)
        val modalFileName: TextView = view.findViewById(R.id.modalFileName)
        val modalFileDetails: TextView = view.findViewById(R.id.modalFileDetails)
        val optionsContainer: LinearLayout = view.findViewById(R.id.optionsContainer)

        // Set header info
        modalFileName.text = file.name
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(file.lastModified)
        modalFileDetails.text = if (file.isDirectory) "Thư mục · $dateStr" else "${formatFileSize(file.size)} · $dateStr"

        if (file.isDirectory) {
            modalIcon.setImageResource(R.drawable.ic_folder)
        } else {
            modalIcon.setImageResource(getIconResource(file))
        }

        // Build options
        val options = buildFileOptions(file)
        populateModalOptions(optionsContainer, options, bottomSheet)

        bottomSheet.show()
    }

    private fun buildFileOptions(file: FileItem): List<FileOption> {
        val options = mutableListOf<FileOption>()

        if (file.isDirectory) {
            options.add(FileOption(R.drawable.ic_download, "Tải ZIP", R.color.colorPrimary) { downloadFile(file) })
            options.add(FileOption(R.drawable.ic_rename, "Đổi tên", R.color.colorAccent) { showRenameDialog(file) })
            options.add(FileOption(R.drawable.ic_delete, "Xóa", android.R.color.holo_red_dark) { showDeleteConfirmDialog(file) })
        } else {
            // Preview option for image/video files
            if (FilePreviewActivity.isImageFile(file.name) || FilePreviewActivity.isVideoFile(file.name)) {
                options.add(FileOption(R.drawable.ic_preview, "Xem", R.color.colorPrimary) { previewFile(file) })
            }
            // Download
            options.add(FileOption(R.drawable.ic_download, "Tải xuống", R.color.colorPrimary) { downloadFile(file) })
            // Edit ALL files (text, unknown extensions, no extension)
            options.add(FileOption(R.drawable.ic_edit, "Sửa", R.color.colorAccent) { editFile(file) })
            // Rename
            options.add(FileOption(R.drawable.ic_rename, "Đổi tên", R.color.colorAccent) { showRenameDialog(file) })
            // Delete
            options.add(FileOption(R.drawable.ic_delete, "Xóa", android.R.color.holo_red_dark) { showDeleteConfirmDialog(file) })
        }

        return options
    }

    data class FileOption(
        val icon: Int,
        val label: String,
        val tintColor: Int,
        val action: () -> Unit
    )

    // ===== Create Modal (BottomSheet) =====

    private fun showCreateModal() {
        val bottomSheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.modal_file_options, null)
        bottomSheet.setContentView(view)

        val modalIcon: ImageView = view.findViewById(R.id.modalIcon)
        val modalFileName: TextView = view.findViewById(R.id.modalFileName)
        val modalFileDetails: TextView = view.findViewById(R.id.modalFileDetails)
        val optionsContainer: LinearLayout = view.findViewById(R.id.optionsContainer)

        modalFileName.text = "Tạo mới"
        modalFileDetails.text = "Chọn thao tác"
        modalIcon.setImageResource(R.drawable.ic_add)

        val options = listOf(
            FileOption(R.drawable.ic_folder, "Thư mục", R.color.colorPrimary) { showCreateFolderDialog() },
            FileOption(R.drawable.ic_text, "File văn bản", R.color.colorAccent) { showCreateFileDialog() },
            FileOption(R.drawable.ic_upload, "Tải lên", R.color.colorPrimary) { openFilePicker() }
        )

        populateModalOptions(optionsContainer, options, bottomSheet)

        bottomSheet.show()
    }

    private fun populateModalOptions(
        container: LinearLayout,
        options: List<FileOption>,
        bottomSheet: BottomSheetDialog
    ) {
        for (option in options) {
            val optionView = LayoutInflater.from(this).inflate(R.layout.item_modal_option, container, false)
            val optionIcon: ImageView = optionView.findViewById(R.id.optionIcon)
            val optionLabel: TextView = optionView.findViewById(R.id.optionLabel)

            optionIcon.setImageResource(option.icon)
            optionIcon.imageTintList = ContextCompat.getColorStateList(this, option.tintColor)
            optionLabel.text = option.label

            optionView.setOnClickListener {
                bottomSheet.dismiss()
                option.action()
            }

            // Use weight for even distribution
            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            optionView.layoutParams = params

            container.addView(optionView)
        }
    }

    // ===== File Operations =====

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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Đổi tên")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    renameFile(file, newName)
                }
            }
            .setNegativeButton("Hủy", null)
            .create()
        dialog.show()
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Xác nhận xóa")
            .setMessage("Bạn có chắc muốn xóa \"${file.name}\" không?")
            .setPositiveButton("Xóa") { _, _ -> deleteFile(file) }
            .setNegativeButton("Hủy", null)
            .create()
        dialog.show()
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

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "Tên thư mục"
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tạo thư mục mới")
            .setView(container)
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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tạo file mới")
            .setView(container)
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

    private fun showManualConnectDialog() {
        val hostInput = EditText(this).apply {
            hint = "IP Address (ví dụ: 192.168.1.100)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(this).apply {
            hint = "Port (mặc định: 9527)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("9527")
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(hostInput)
            addView(portInput)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kết nối thủ công")
            .setView(container)
            .setPositiveButton("Kết nối") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 9527
                if (host.isNotEmpty()) {
                    val peer = PeerDevice("Manual ($host)", host, port)
                    connectToPeer(peer)
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

        fileAdapter.currentPeer = null
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
        if (isBrowsingFiles) {
            if (pathHistory.isNotEmpty()) {
                pathHistory.removeAt(pathHistory.size - 1)
                if (pathHistory.isEmpty()) {
                    showPeerList()
                } else {
                    currentPath = pathHistory.last()
                    browsePath(currentPath)
                }
            } else {
                // At root level of browsing, go back to peer list
                showPeerList()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ===== Helper methods =====

    private fun getIconResource(file: FileItem): Int {
        val name = file.name.lowercase()
        return when {
            file.isDirectory -> R.drawable.ic_folder
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") -> R.drawable.ic_image
            name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") -> R.drawable.ic_video
            name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") -> R.drawable.ic_audio
            name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".md") -> R.drawable.ic_text
            name.endsWith(".pdf") -> R.drawable.ic_pdf
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> R.drawable.ic_archive
            name.endsWith(".apk") -> R.drawable.ic_apk
            else -> R.drawable.ic_file
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}

private object LogHelper {
    fun e(tag: String, msg: String, e: Exception) {
        try {
            android.util.Log.e(tag, msg, e)
        } catch (_: Exception) {}
    }
}

package com.p2pfileshare.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.DirectoryInfo
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ========== Data Classes ==========

    data class FileOption(
        val label: String,
        val iconRes: Int,
        val action: () -> Unit
    )

    enum class SortMode { NAME, SIZE, DATE, TYPE }

    enum class EmptyStateType { NO_PEERS, EMPTY_DIR, NO_SEARCH_RESULTS }

    // ========== Preferences & API ==========

    private lateinit var prefs: PreferencesManager
    private lateinit var apiClient: ApiClient

    // ========== Views ==========

    private lateinit var tvStatus: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var ivEmptyIcon: ImageView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var recyclerViewGrid: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var breadcrumbScroll: HorizontalScrollView
    private lateinit var breadcrumbContainer: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSearchClose: ImageButton
    private lateinit var sortBar: HorizontalScrollView
    private lateinit var chipSortName: TextView
    private lateinit var chipSortSize: TextView
    private lateinit var chipSortDate: TextView
    private lateinit var chipSortType: TextView
    private lateinit var transferBar: LinearLayout
    private lateinit var tvTransferStatus: TextView
    private lateinit var transferProgress: ProgressBar
    private lateinit var selectionBar: LinearLayout
    private lateinit var btnSelectionClose: ImageButton
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnSelectAll: ImageButton
    private lateinit var btnBatchDownload: ImageButton
    private lateinit var btnBatchCopy: ImageButton
    private lateinit var btnBatchMove: ImageButton
    private lateinit var btnBatchDelete: ImageButton

    // ========== Adapters ==========

    private lateinit var peerAdapter: PeerAdapter
    private lateinit var fileAdapter: FileListAdapter
    private lateinit var fileGridAdapter: FileGridAdapter

    // ========== State ==========

    private var currentPeer: PeerDevice? = null
    private var currentPath: String = "/"
    private var pathHistory = mutableListOf<String>()
    private var isBrowsingFiles = false

    // Sort state
    private var currentSortMode = SortMode.NAME
    private var allFiles = listOf<FileItem>()

    // Selection state
    private var isSelectionMode = false
    private val selectedFiles = mutableSetOf<FileItem>()

    // View state
    private var isGridView = false

    private val UPLOAD_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 100

    // ========== Lifecycle ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            prefs = PreferencesManager(this)
            apiClient = ApiClient()
            isGridView = prefs.isGridView

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
        val viewToggleItem = menu.findItem(R.id.action_view_toggle)
        viewToggleItem?.setIcon(if (isGridView) R.drawable.ic_list else R.drawable.ic_grid)
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
            R.id.action_search -> {
                toggleSearchBar()
                true
            }
            R.id.action_sort -> {
                toggleSortBar()
                true
            }
            R.id.action_view_toggle -> {
                toggleViewMode(item)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("DEPRECATION")
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

    // ========== Initialization ==========

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerViewGrid = findViewById(R.id.recyclerViewGrid)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        fabAdd = findViewById(R.id.fabAdd)
        breadcrumbScroll = findViewById(R.id.breadcrumbScroll)
        breadcrumbContainer = findViewById(R.id.breadcrumbContainer)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnSearchClose = findViewById(R.id.btnSearchClose)
        sortBar = findViewById(R.id.sortBar)
        chipSortName = findViewById(R.id.chipSortName)
        chipSortSize = findViewById(R.id.chipSortSize)
        chipSortDate = findViewById(R.id.chipSortDate)
        chipSortType = findViewById(R.id.chipSortType)
        transferBar = findViewById(R.id.transferBar)
        tvTransferStatus = findViewById(R.id.tvTransferStatus)
        transferProgress = findViewById(R.id.transferProgress)
        selectionBar = findViewById(R.id.selectionBar)
        btnSelectionClose = findViewById(R.id.btnSelectionClose)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnBatchDownload = findViewById(R.id.btnBatchDownload)
        btnBatchCopy = findViewById(R.id.btnBatchCopy)
        btnBatchMove = findViewById(R.id.btnBatchMove)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "P2P File Share"

        tvStatus.text = "Đang kiểm tra quyền..."

        if (isGridView) {
            recyclerView.visibility = View.GONE
            recyclerViewGrid.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter(this) { peer -> connectToPeer(peer) }
        fileAdapter = FileListAdapter(
            this,
            onItemClick = { file -> onFileItemClick(file) },
            onItemLongClick = { file -> onFileItemLongClick(file) },
            onSelectionToggle = { file -> toggleFileSelection(file) }
        )
        fileGridAdapter = FileGridAdapter(
            this,
            onItemClick = { file -> onFileItemClick(file) },
            onItemLongClick = { file -> onFileItemLongClick(file) },
            onSelectionToggle = { file -> toggleFileSelection(file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerViewGrid.layoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        recyclerViewGrid.adapter = fileGridAdapter

        recyclerView.adapter = peerAdapter
    }

    private fun setupListeners() {
        // Swipe refresh
        swipeRefresh.setOnRefreshListener {
            if (isBrowsingFiles && currentPeer != null) {
                browsePath(currentPath)
            } else {
                refreshPeers()
            }
            swipeRefresh.isRefreshing = false
        }

        // FAB
        fabAdd.setOnClickListener {
            if (isBrowsingFiles && currentPeer != null) {
                showCreateDialog()
            }
        }

        // Search
        btnSearchClose.setOnClickListener { hideSearchBar() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFiles(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Sort chips
        chipSortName.setOnClickListener { setSortMode(SortMode.NAME) }
        chipSortSize.setOnClickListener { setSortMode(SortMode.SIZE) }
        chipSortDate.setOnClickListener { setSortMode(SortMode.DATE) }
        chipSortType.setOnClickListener { setSortMode(SortMode.TYPE) }

        // Selection bar buttons
        btnSelectionClose.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { selectAllFiles() }
        btnBatchDownload.setOnClickListener { batchDownload() }
        btnBatchCopy.setOnClickListener { batchCopy() }
        btnBatchMove.setOnClickListener { batchMove() }
        btnBatchDelete.setOnClickListener { batchDelete() }
    }

    // ========== Permissions ==========

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

        @Suppress("DEPRECATION")
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

    // ========== Peer Discovery ==========

    private fun refreshPeers() {
        val peers = P2PService.getDiscoveredPeers()
        peerAdapter.setPeers(peers)
        if (peers.isEmpty()) {
            showEmptyState(EmptyStateType.NO_PEERS)
        } else {
            hideEmptyState()
        }
    }

    // ========== Connect & Browse ==========

    private fun connectToPeer(peer: PeerDevice) {
        currentPeer = peer
        currentPath = "/"
        pathHistory.clear()
        isBrowsingFiles = true

        supportActionBar?.title = peer.name
        setFileAdapterActive()
        fabAdd.show()

        browsePath("/")
        loadPeerStorageInfo(peer)
    }

    private fun loadPeerStorageInfo(peer: PeerDevice) {
        lifecycleScope.launch {
            try {
                val storageInfo = apiClient.getStorageInfo(peer)
                withContext(Dispatchers.Main) {
                    if (storageInfo != null) {
                        peerAdapter.updateStorageInfo(peer, storageInfo)
                    }
                }
            } catch (e: Exception) {
                LogHelper.e("MainActivity", "Failed to get storage info", e)
            }
        }
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
                        allFiles = dirInfo.files
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = path
                        updateBreadcrumb(path)
                        applySortAndFilter()

                        if (dirInfo.files.isEmpty()) {
                            val searchQuery = etSearch.text.toString().trim()
                            if (searchQuery.isNotEmpty()) {
                                showEmptyState(EmptyStateType.NO_SEARCH_RESULTS)
                            } else {
                                showEmptyState(EmptyStateType.EMPTY_DIR)
                            }
                        } else {
                            hideEmptyState()
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

    // ========== Breadcrumb Navigation ==========

    private fun updateBreadcrumb(path: String) {
        breadcrumbContainer.removeAllViews()
        if (!isBrowsingFiles) {
            breadcrumbScroll.visibility = View.GONE
            return
        }

        breadcrumbScroll.visibility = View.VISIBLE

        // Root segment
        addBreadcrumbSegment("/", "Thiết bị")

        if (path == "/") {
            breadcrumbScroll.post { breadcrumbScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
            return
        }

        // Split path into segments
        val segments = path.trim('/').split('/')
        var accumulatedPath = ""

        for (segment in segments) {
            if (segment.isEmpty()) continue

            // Add separator ">"
            addBreadcrumbSeparator()

            accumulatedPath += "/$segment"
            val targetPath = accumulatedPath
            addBreadcrumbSegment(targetPath, segment)
        }

        breadcrumbScroll.post { breadcrumbScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun addBreadcrumbSegment(targetPath: String, label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setBackgroundResource(R.drawable.bg_breadcrumb)
            setPadding(12, 4, 12, 4)
            maxLines = 1
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (targetPath != currentPath) {
                    browsePath(targetPath)
                }
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.CENTER_VERTICAL
        tv.layoutParams = params
        breadcrumbContainer.addView(tv)
    }

    private fun addBreadcrumbSeparator() {
        val tv = TextView(this).apply {
            text = ">"
            textSize = 12f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(6, 4, 6, 4)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.CENTER_VERTICAL
        tv.layoutParams = params
        breadcrumbContainer.addView(tv)
    }

    // ========== File Item Click ==========

    private fun onFileItemClick(file: FileItem) {
        if (isSelectionMode) {
            toggleFileSelection(file)
            return
        }

        if (file.isDirectory) {
            pathHistory.add(currentPath)
            browsePath(file.path)
        } else {
            showFileOptionsModal(file)
        }
    }

    private fun onFileItemLongClick(file: FileItem) {
        if (isSelectionMode) {
            toggleFileSelection(file)
        } else {
            enterSelectionMode(file)
        }
    }

    // ========== File Options Modal (BottomSheetDialog) ==========

    private fun showFileOptionsModal(file: FileItem) {
        val options = populateModalOptions(file)

        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 64)
        }

        // Title
        val title = TextView(this).apply {
            text = file.name
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        // Options rows
        for (option in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    option.action()
                }
            }

            val icon = ImageView(this).apply {
                setImageResource(option.iconRes)
                setColorFilter(Color.parseColor("#757575"))
                val lp = LinearLayout.LayoutParams(48, 48)
                lp.setMargins(0, 0, 24, 0)
                layoutParams = lp
            }
            row.addView(icon)

            val label = TextView(this).apply {
                text = option.label
                textSize = 15f
                setTextColor(Color.parseColor("#212121"))
            }
            row.addView(label)

            container.addView(row)
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun populateModalOptions(file: FileItem): List<FileOption> {
        val options = mutableListOf<FileOption>()

        if (file.isDirectory) {
            options.add(FileOption("Tải xuống (ZIP)", R.drawable.ic_storage) { downloadFile(file) })
            options.add(FileOption("Sao chép", R.drawable.ic_copy) { showCopyDialog(file) })
            options.add(FileOption("Di chuyển", R.drawable.ic_move) { showMoveDialog(file) })
            options.add(FileOption("Đổi tên", R.drawable.ic_share) { showRenameDialog(file) })
            options.add(FileOption("Xóa", R.drawable.ic_close) { showDeleteConfirmDialog(file) })
        } else {
            options.add(FileOption("Tải xuống", R.drawable.ic_storage) { downloadFile(file) })

            val isTextFile = file.name.endsWith(".txt") || file.name.endsWith(".log") ||
                    file.name.endsWith(".md") || file.name.endsWith(".json") ||
                    file.name.endsWith(".xml") || file.name.endsWith(".html") ||
                    file.name.endsWith(".css") || file.name.endsWith(".js") ||
                    file.name.endsWith(".csv") || file.name.endsWith(".properties")
            if (isTextFile) {
                options.add(FileOption("Chỉnh sửa", R.drawable.ic_text) { editFile(file) })
            }

            // ZIP Viewer - browse contents of ZIP files
            val isZipFile = file.name.lowercase().endsWith(".zip")
            if (isZipFile) {
                options.add(FileOption("Xem nội dung ZIP", R.drawable.ic_archive) { viewZipFile(file) })
            }

            options.add(FileOption("Sao chép", R.drawable.ic_copy) { showCopyDialog(file) })
            options.add(FileOption("Di chuyển", R.drawable.ic_move) { showMoveDialog(file) })
            options.add(FileOption("Đổi tên", R.drawable.ic_share) { showRenameDialog(file) })
            options.add(FileOption("Xóa", R.drawable.ic_close) { showDeleteConfirmDialog(file) })
        }

        return options
    }

    // ========== ZIP Viewer ==========

    private fun viewZipFile(file: FileItem) {
        val peer = currentPeer ?: return
        val intent = Intent(this, ZipViewerActivity::class.java)
        intent.putExtra("peer_host", peer.host)
        intent.putExtra("peer_port", peer.port)
        intent.putExtra("zip_path", file.path)
        intent.putExtra("zip_name", file.name)
        startActivity(intent)
    }

    // ========== File Operations ==========

    private fun downloadFile(file: FileItem) {
        val peer = currentPeer ?: return
        val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        showTransferProgress("Đang tải xuống: ${file.name}")
        lifecycleScope.launch {
            val success = apiClient.downloadFile(peer, file.path, destDir)
            withContext(Dispatchers.Main) {
                hideTransferProgress()
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

    // ========== Create Operations ==========

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
        showTransferProgress("Đang tải lên...")
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
                    hideTransferProgress()
                    if (success) {
                        Toast.makeText(this@MainActivity, "Đã tải lên", Toast.LENGTH_SHORT).show()
                        browsePath(currentPath)
                    } else {
                        Toast.makeText(this@MainActivity, "Tải lên thất bại", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideTransferProgress()
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========== Manual Connect ==========

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
                    connectToPeer(peer)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ========== Navigation ==========

    private fun showPeerList() {
        isBrowsingFiles = false
        currentPeer = null
        currentPath = "/"
        pathHistory.clear()
        exitSelectionMode()

        supportActionBar?.title = "P2P File Share"
        recyclerView.adapter = peerAdapter
        recyclerView.visibility = View.VISIBLE
        recyclerViewGrid.visibility = View.GONE
        fabAdd.hide()
        breadcrumbScroll.visibility = View.GONE
        searchBar.visibility = View.GONE
        sortBar.visibility = View.GONE
        tvStatus.text = "Đang tìm thiết bị cùng WiFi..."
        tvStatus.visibility = View.VISIBLE

        showEmptyState(EmptyStateType.NO_PEERS)
        refreshPeers()
    }

    private fun setFileAdapterActive() {
        if (isGridView) {
            recyclerView.visibility = View.GONE
            recyclerViewGrid.visibility = View.VISIBLE
            recyclerViewGrid.adapter = fileGridAdapter
        } else {
            recyclerView.visibility = View.VISIBLE
            recyclerViewGrid.visibility = View.GONE
            recyclerView.adapter = fileAdapter
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        val activeRecyclerView = if (isGridView && isBrowsingFiles) recyclerViewGrid else recyclerView
        activeRecyclerView.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
            return
        }

        if (isBrowsingFiles && pathHistory.isNotEmpty()) {
            val previousPath = pathHistory.removeAt(pathHistory.size - 1)
            currentPath = previousPath
            browsePath(currentPath)
        } else if (isBrowsingFiles) {
            showPeerList()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ========== Search ==========

    private fun toggleSearchBar() {
        if (searchBar.visibility == View.VISIBLE) {
            hideSearchBar()
        } else {
            searchBar.visibility = View.VISIBLE
            etSearch.setText("")
            etSearch.requestFocus()
            showKeyboard(etSearch)
        }
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        etSearch.setText("")
        filterFiles("")
    }

    private fun filterFiles(query: String) {
        if (!isBrowsingFiles) return

        if (query.isEmpty()) {
            applySortAndFilter()
            if (allFiles.isEmpty()) {
                showEmptyState(EmptyStateType.EMPTY_DIR)
            } else {
                hideEmptyState()
            }
        } else {
            val filtered = allFiles.filter {
                it.name.contains(query, ignoreCase = true)
            }
            applySortAndFilter(filtered)
            if (filtered.isEmpty()) {
                showEmptyState(EmptyStateType.NO_SEARCH_RESULTS)
            } else {
                hideEmptyState()
            }
        }
    }

    private fun showKeyboard(view: View) {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            LogHelper.e("MainActivity", "Failed to show keyboard", e)
        }
    }

    // ========== Sort ==========

    private fun toggleSortBar() {
        if (sortBar.visibility == View.VISIBLE) {
            sortBar.visibility = View.GONE
        } else {
            sortBar.visibility = View.VISIBLE
        }
    }

    private fun setSortMode(mode: SortMode) {
        currentSortMode = mode
        updateSortChips()
        applySortAndFilter()
    }

    private fun updateSortChips() {
        val selectedBg = R.drawable.bg_chip_selected
        val normalBg = R.drawable.bg_chip_normal
        val selectedColor = Color.parseColor("#FFFFFF")
        val normalColor = Color.parseColor("#757575")

        chipSortName.setBackgroundResource(if (currentSortMode == SortMode.NAME) selectedBg else normalBg)
        chipSortName.setTextColor(if (currentSortMode == SortMode.NAME) selectedColor else normalColor)

        chipSortSize.setBackgroundResource(if (currentSortMode == SortMode.SIZE) selectedBg else normalBg)
        chipSortSize.setTextColor(if (currentSortMode == SortMode.SIZE) selectedColor else normalColor)

        chipSortDate.setBackgroundResource(if (currentSortMode == SortMode.DATE) selectedBg else normalBg)
        chipSortDate.setTextColor(if (currentSortMode == SortMode.DATE) selectedColor else normalColor)

        chipSortType.setBackgroundResource(if (currentSortMode == SortMode.TYPE) selectedBg else normalBg)
        chipSortType.setTextColor(if (currentSortMode == SortMode.TYPE) selectedColor else normalColor)
    }

    private fun applySortAndFilter(files: List<FileItem>? = null) {
        val source = files ?: allFiles
        val sorted = when (currentSortMode) {
            SortMode.NAME -> source.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortMode.SIZE -> source.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
            SortMode.DATE -> source.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified })).reversed()
            SortMode.TYPE -> source.sortedWith(compareBy({ !it.isDirectory }, { getFileExtension(it.name).lowercase() }, { it.name.lowercase() }))
        }

        fileAdapter.setItems(sorted)
        fileGridAdapter.setItems(sorted)
        updateSelectionInAdapters()
    }

    private fun getFileExtension(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex >= 0 && dotIndex < name.length - 1) {
            name.substring(dotIndex + 1)
        } else {
            ""
        }
    }

    // ========== Grid/List View Toggle ==========

    private fun toggleViewMode(menuItem: MenuItem) {
        isGridView = !isGridView
        prefs.isGridView = isGridView

        menuItem.setIcon(if (isGridView) R.drawable.ic_list else R.drawable.ic_grid)

        if (isBrowsingFiles) {
            if (isGridView) {
                recyclerView.visibility = View.GONE
                recyclerViewGrid.visibility = View.VISIBLE
                recyclerViewGrid.adapter = fileGridAdapter
            } else {
                recyclerView.visibility = View.VISIBLE
                recyclerViewGrid.visibility = View.GONE
                recyclerView.adapter = fileAdapter
            }
            applySortAndFilter()
        }
    }

    // ========== Multi-Select Mode ==========

    private fun enterSelectionMode(file: FileItem) {
        isSelectionMode = true
        selectedFiles.clear()
        selectedFiles.add(file)
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
        updateSelectionInAdapters()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedFiles.clear()
        selectionBar.visibility = View.GONE
        updateSelectionInAdapters()
    }

    private fun toggleFileSelection(file: FileItem) {
        if (!isSelectionMode) {
            enterSelectionMode(file)
            return
        }

        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
            if (selectedFiles.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedFiles.add(file)
        }

        updateSelectionCount()
        updateSelectionInAdapters()
    }

    private fun selectAllFiles() {
        selectedFiles.clear()
        selectedFiles.addAll(allFiles)
        updateSelectionCount()
        updateSelectionInAdapters()
    }

    private fun updateSelectionCount() {
        tvSelectionCount.text = "${selectedFiles.size} đã chọn"
    }

    private fun updateSelectionInAdapters() {
        fileAdapter.setSelectionMode(isSelectionMode, selectedFiles)
        fileGridAdapter.setSelectionMode(isSelectionMode, selectedFiles)
    }

    // ========== Batch Operations ==========

    private fun batchDownload() {
        if (selectedFiles.isEmpty()) return
        val peer = currentPeer ?: return
        val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = selectedFiles.toList()

        showTransferProgress("Đang tải xuống ${files.size} file...")
        lifecycleScope.launch {
            var successCount = 0
            for (file in files) {
                val success = apiClient.downloadFile(peer, file.path, destDir)
                if (success) successCount++
            }
            withContext(Dispatchers.Main) {
                hideTransferProgress()
                Toast.makeText(this@MainActivity, "Đã tải xuống $successCount/${files.size} file", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                browsePath(currentPath)
            }
        }
    }

    private fun batchDelete() {
        if (selectedFiles.isEmpty()) return
        val files = selectedFiles.toList()

        AlertDialog.Builder(this)
            .setTitle("Xác nhận xóa")
            .setMessage("Bạn có chắc muốn xóa ${files.size} file đã chọn?")
            .setPositiveButton("Xóa") { _, _ ->
                performBatchDelete(files)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun performBatchDelete(files: List<FileItem>) {
        val peer = currentPeer ?: return
        lifecycleScope.launch {
            var successCount = 0
            for (file in files) {
                val success = apiClient.deleteFile(peer, file.path)
                if (success) successCount++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Đã xóa $successCount/${files.size} file", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                browsePath(currentPath)
            }
        }
    }

    private fun batchCopy() {
        if (selectedFiles.isEmpty()) return
        showDestinationPickerDialog { destPath ->
            performBatchCopy(selectedFiles.toList(), destPath)
        }
    }

    private fun batchMove() {
        if (selectedFiles.isEmpty()) return
        showDestinationPickerDialog { destPath ->
            performBatchMove(selectedFiles.toList(), destPath)
        }
    }

    private fun performBatchCopy(files: List<FileItem>, destPath: String) {
        val peer = currentPeer ?: return
        showTransferProgress("Đang sao chép ${files.size} file...")
        lifecycleScope.launch {
            var successCount = 0
            for (file in files) {
                val success = apiClient.copyFile(peer, file.path, destPath)
                if (success) successCount++
            }
            withContext(Dispatchers.Main) {
                hideTransferProgress()
                Toast.makeText(this@MainActivity, "Đã sao chép $successCount/${files.size} file", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                browsePath(currentPath)
            }
        }
    }

    private fun performBatchMove(files: List<FileItem>, destPath: String) {
        val peer = currentPeer ?: return
        showTransferProgress("Đang di chuyển ${files.size} file...")
        lifecycleScope.launch {
            var successCount = 0
            for (file in files) {
                val success = apiClient.moveFile(peer, file.path, destPath)
                if (success) successCount++
            }
            withContext(Dispatchers.Main) {
                hideTransferProgress()
                Toast.makeText(this@MainActivity, "Đã di chuyển $successCount/${files.size} file", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                browsePath(currentPath)
            }
        }
    }

    // ========== Copy/Move Individual Files ==========

    private fun showCopyDialog(file: FileItem) {
        showDestinationPickerDialog { destPath ->
            copyFile(file, destPath)
        }
    }

    private fun showMoveDialog(file: FileItem) {
        showDestinationPickerDialog { destPath ->
            moveFile(file, destPath)
        }
    }

    private fun copyFile(file: FileItem, destPath: String) {
        val peer = currentPeer ?: return
        showTransferProgress("Đang sao chép: ${file.name}")
        lifecycleScope.launch {
            val success = apiClient.copyFile(peer, file.path, destPath)
            withContext(Dispatchers.Main) {
                hideTransferProgress()
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã sao chép", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Sao chép thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun moveFile(file: FileItem, destPath: String) {
        val peer = currentPeer ?: return
        showTransferProgress("Đang di chuyển: ${file.name}")
        lifecycleScope.launch {
            val success = apiClient.moveFile(peer, file.path, destPath)
            withContext(Dispatchers.Main) {
                hideTransferProgress()
                if (success) {
                    Toast.makeText(this@MainActivity, "Đã di chuyển", Toast.LENGTH_SHORT).show()
                    browsePath(currentPath)
                } else {
                    Toast.makeText(this@MainActivity, "Di chuyển thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========== Destination Picker Dialog ==========

    private fun showDestinationPickerDialog(onDestinationSelected: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Đường dẫn đích (ví dụ: /sdcard/Documents)"
            setText(currentPath)
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Chọn thư mục đích")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val destPath = input.text.toString().trim()
                if (destPath.isNotEmpty()) {
                    onDestinationSelected(destPath)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ========== Transfer Progress ==========

    private fun showTransferProgress(status: String) {
        transferBar.visibility = View.VISIBLE
        tvTransferStatus.text = status
        transferProgress.progress = 0
    }

    private fun hideTransferProgress() {
        transferBar.visibility = View.GONE
    }

    // ========== Empty States ==========

    private fun showEmptyState(type: EmptyStateType) {
        emptyStateContainer.visibility = View.VISIBLE
        when (type) {
            EmptyStateType.NO_PEERS -> {
                ivEmptyIcon.setImageResource(R.drawable.ic_phone)
                tvEmptyState.text = "Chưa tìm thấy thiết bị nào.\nĐảm bảo 2 máy cùng WiFi và đã mở app."
            }
            EmptyStateType.EMPTY_DIR -> {
                ivEmptyIcon.setImageResource(R.drawable.ic_folder)
                tvEmptyState.text = "Thư mục trống"
            }
            EmptyStateType.NO_SEARCH_RESULTS -> {
                ivEmptyIcon.setImageResource(R.drawable.ic_search)
                tvEmptyState.text = "Không tìm thấy kết quả"
            }
        }
    }

    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
    }

    // ========== Utility Methods ==========

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    private fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp")
    }

    private fun getIconForFile(item: FileItem): Int {
        val name = item.name.lowercase()
        return when {
            item.isDirectory -> R.drawable.ic_folder
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") -> R.drawable.ic_image
            name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") ||
                    name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") -> R.drawable.ic_video
            name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") ||
                    name.endsWith(".ogg") || name.endsWith(".aac") -> R.drawable.ic_audio
            name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".md") ||
                    name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".csv") -> R.drawable.ic_text
            name.endsWith(".pdf") -> R.drawable.ic_pdf
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") ||
                    name.endsWith(".tar") || name.endsWith(".gz") -> R.drawable.ic_archive
            name.endsWith(".apk") -> R.drawable.ic_apk
            else -> R.drawable.ic_file
        }
    }

    private fun getIconColorForFile(item: FileItem): Int {
        val name = item.name.lowercase()
        return when {
            item.isDirectory -> Color.parseColor("#FFA726")
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") -> Color.parseColor("#4CAF50")
            name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") ||
                    name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv") -> Color.parseColor("#E53935")
            name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") ||
                    name.endsWith(".ogg") || name.endsWith(".aac") -> Color.parseColor("#AB47BC")
            name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".md") ||
                    name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".csv") -> Color.parseColor("#42A5F5")
            name.endsWith(".pdf") -> Color.parseColor("#E53935")
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> Color.parseColor("#8D6E63")
            name.endsWith(".apk") -> Color.parseColor("#66BB6A")
            else -> Color.parseColor("#78909C")
        }
    }

    private fun getFileTypeLabel(item: FileItem): String {
        if (item.isDirectory) return "THƯ MỤC"
        val dotIndex = item.name.lastIndexOf('.')
        return if (dotIndex >= 0 && dotIndex < item.name.length - 1) {
            item.name.substring(dotIndex + 1).uppercase()
        } else {
            "FILE"
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

    private fun formatFileSizeShort(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.0f".format(bytes / (1024.0 * 1024))}MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))}GB"
        }
    }

    private fun getThumbnailUrl(file: FileItem): String? {
        if (!isImageFile(file.name)) return null
        val peer = currentPeer ?: return null
        val encodedPath = try {
            URLEncoder.encode(file.path, "UTF-8")
        } catch (e: Exception) {
            return null
        }
        return "http://${peer.host}:${peer.port}/api/download?path=$encodedPath"
    }

    // ========== Inner Class: PeerAdapter ==========

    inner class PeerAdapter(
        private val context: android.content.Context,
        private val onItemClick: (PeerDevice) -> Unit
    ) : RecyclerView.Adapter<PeerAdapter.ViewHolder>() {

        private val peers = mutableListOf<PeerDevice>()
        private val storageInfoMap = mutableMapOf<String, String>()

        fun setPeers(newPeers: List<PeerDevice>) {
            peers.clear()
            peers.addAll(newPeers)
            notifyDataSetChanged()
        }

        fun addPeer(peer: PeerDevice) {
            val existing = peers.indexOfFirst { it.host == peer.host }
            if (existing >= 0) {
                peers[existing] = peer
                notifyItemChanged(existing)
            } else {
                peers.add(peer)
                notifyItemInserted(peers.size - 1)
            }
        }

        fun removePeer(name: String) {
            val index = peers.indexOfFirst { it.name == name }
            if (index >= 0) {
                peers.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        fun updateStorageInfo(peer: PeerDevice, info: String) {
            storageInfoMap[peer.host] = info
            val index = peers.indexOfFirst { it.host == peer.host }
            if (index >= 0) {
                notifyItemChanged(index)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_peer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(peers[position])
        }

        override fun getItemCount() = peers.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvPeerName: TextView = view.findViewById(R.id.tvPeerName)
            private val tvPeerAddress: TextView = view.findViewById(R.id.tvPeerAddress)
            private val tvPeerStorage: TextView = view.findViewById(R.id.tvPeerStorage)
            private val viewOnlineDot: View = view.findViewById(R.id.viewOnlineDot)

            fun bind(peer: PeerDevice) {
                tvPeerName.text = peer.name
                tvPeerAddress.text = "${peer.host}:${peer.port}"
                tvPeerStorage.text = storageInfoMap[peer.host] ?: "Đang tải..."
                try {
                    viewOnlineDot.setBackgroundColor(Color.parseColor("#4CAF50"))
                } catch (e: Exception) {
                    LogHelper.e("PeerAdapter", "Failed to set online dot color", e)
                }
                itemView.setOnClickListener { onItemClick(peer) }
            }
        }
    }

    // ========== Inner Class: FileListAdapter (List View) ==========

    inner class FileListAdapter(
        private val context: android.content.Context,
        private val onItemClick: (FileItem) -> Unit,
        private val onItemLongClick: (FileItem) -> Unit,
        private val onSelectionToggle: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

        private val items = mutableListOf<FileItem>()
        private var selectionMode = false
        private var selectedItems = setOf<FileItem>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun setItems(newItems: List<FileItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun clear() {
            items.clear()
            notifyDataSetChanged()
        }

        fun setSelectionMode(mode: Boolean, selected: Set<FileItem>) {
            selectionMode = mode
            selectedItems = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val selectionCheckContainer: FrameLayout = view.findViewById(R.id.selectionCheckContainer)
            private val viewSelectionCircle: View = view.findViewById(R.id.viewSelectionCircle)
            private val ivSelectionCheck: ImageView = view.findViewById(R.id.ivSelectionCheck)
            private val viewIconBg: View = view.findViewById(R.id.viewIconBg)
            private val ivFileIcon: ImageView = view.findViewById(R.id.ivFileIcon)
            private val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            private val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
            private val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            private val tvFileDetails: TextView = view.findViewById(R.id.tvFileDetails)
            private val tvFileType: TextView = view.findViewById(R.id.tvFileType)
            private val ivArrow: ImageView = view.findViewById(R.id.ivArrow)

            fun bind(item: FileItem) {
                tvFileName.text = item.name

                // Selection mode UI
                if (selectionMode) {
                    selectionCheckContainer.visibility = View.VISIBLE
                    val isSelected = selectedItems.contains(item)
                    ivSelectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                    try {
                        viewSelectionCircle.setBackgroundColor(
                            if (isSelected) Color.parseColor("#1565C0") else Color.parseColor("#E0E0E0")
                        )
                    } catch (e: Exception) {
                        LogHelper.e("FileListAdapter", "Failed to set selection color", e)
                    }
                } else {
                    selectionCheckContainer.visibility = View.GONE
                }

                // Icon / Thumbnail
                val isImage = !item.isDirectory && isImageFile(item.name)
                if (isImage) {
                    ivThumbnail.visibility = View.VISIBLE
                    viewIconBg.visibility = View.GONE
                    ivFileIcon.visibility = View.GONE
                    val thumbUrl = getThumbnailUrl(item)
                    if (thumbUrl != null) {
                        ivThumbnail.load(thumbUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_image)
                            error(R.drawable.ic_image)
                        }
                    }
                } else {
                    ivThumbnail.visibility = View.GONE
                    viewIconBg.visibility = View.VISIBLE
                    ivFileIcon.visibility = View.VISIBLE
                    ivFileIcon.setImageResource(getIconForFile(item))
                    val iconColor = getIconColorForFile(item)
                    ivFileIcon.setColorFilter(iconColor)
                }

                // Play overlay for video
                if (!item.isDirectory && isVideoFile(item.name)) {
                    ivPlayOverlay.visibility = View.VISIBLE
                } else {
                    ivPlayOverlay.visibility = View.GONE
                }

                // File details
                if (item.isDirectory) {
                    tvFileDetails.text = dateFormat.format(item.lastModified)
                    ivArrow.visibility = View.VISIBLE
                } else {
                    tvFileDetails.text = "${formatFileSize(item.size)} · ${dateFormat.format(item.lastModified)}"
                    ivArrow.visibility = View.GONE
                }

                // File type label
                if (!item.isDirectory) {
                    tvFileType.visibility = View.VISIBLE
                    tvFileType.text = getFileTypeLabel(item)
                    tvFileType.setTextColor(getIconColorForFile(item))
                } else {
                    tvFileType.visibility = View.GONE
                }

                // Click listeners
                itemView.setOnClickListener { onItemClick(item) }
                itemView.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }

                selectionCheckContainer.setOnClickListener { onSelectionToggle(item) }
            }
        }
    }

    // ========== Inner Class: FileGridAdapter (Grid View) ==========

    inner class FileGridAdapter(
        private val context: android.content.Context,
        private val onItemClick: (FileItem) -> Unit,
        private val onItemLongClick: (FileItem) -> Unit,
        private val onSelectionToggle: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileGridAdapter.ViewHolder>() {

        private val items = mutableListOf<FileItem>()
        private var selectionMode = false
        private var selectedItems = setOf<FileItem>()

        fun setItems(newItems: List<FileItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun setSelectionMode(mode: Boolean, selected: Set<FileItem>) {
            selectionMode = mode
            selectedItems = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_file_grid, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivThumbnailGrid: ImageView = view.findViewById(R.id.ivThumbnailGrid)
            private val viewIconBgGrid: View = view.findViewById(R.id.viewIconBgGrid)
            private val ivFileIconGrid: ImageView = view.findViewById(R.id.ivFileIconGrid)
            private val ivPlayOverlayGrid: ImageView = view.findViewById(R.id.ivPlayOverlayGrid)
            private val ivSelectionCheckGrid: ImageView = view.findViewById(R.id.ivSelectionCheckGrid)
            private val tvFileNameGrid: TextView = view.findViewById(R.id.tvFileNameGrid)
            private val tvFileSizeGrid: TextView = view.findViewById(R.id.tvFileSizeGrid)

            fun bind(item: FileItem) {
                tvFileNameGrid.text = item.name
                tvFileSizeGrid.text = if (item.isDirectory) "Thư mục" else formatFileSizeShort(item.size)

                // Selection
                if (selectionMode) {
                    ivSelectionCheckGrid.visibility = View.VISIBLE
                    val isSelected = selectedItems.contains(item)
                    ivSelectionCheckGrid.visibility = if (isSelected) View.VISIBLE else View.GONE
                    if (isSelected) {
                        try {
                            ivSelectionCheckGrid.setBackgroundColor(Color.parseColor("#1565C0"))
                        } catch (e: Exception) {
                            LogHelper.e("FileGridAdapter", "Failed to set selection bg", e)
                        }
                    }
                } else {
                    ivSelectionCheckGrid.visibility = View.GONE
                }

                // Thumbnail / Icon
                val isImage = !item.isDirectory && isImageFile(item.name)
                if (isImage) {
                    ivThumbnailGrid.visibility = View.VISIBLE
                    viewIconBgGrid.visibility = View.GONE
                    ivFileIconGrid.visibility = View.GONE
                    val thumbUrl = getThumbnailUrl(item)
                    if (thumbUrl != null) {
                        ivThumbnailGrid.load(thumbUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_image)
                            error(R.drawable.ic_image)
                        }
                    }
                } else {
                    ivThumbnailGrid.visibility = View.GONE
                    viewIconBgGrid.visibility = View.VISIBLE
                    ivFileIconGrid.visibility = View.VISIBLE
                    ivFileIconGrid.setImageResource(getIconForFile(item))
                    val iconColor = getIconColorForFile(item)
                    ivFileIconGrid.setColorFilter(iconColor)
                }

                // Play overlay for video
                if (!item.isDirectory && isVideoFile(item.name)) {
                    ivPlayOverlayGrid.visibility = View.VISIBLE
                } else {
                    ivPlayOverlayGrid.visibility = View.GONE
                }

                // Click listeners
                itemView.setOnClickListener { onItemClick(item) }
                itemView.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
            }
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

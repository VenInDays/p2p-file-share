package com.p2pfileshare.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.PeerDevice
import com.p2pfileshare.app.model.ZipEntryItem
import com.p2pfileshare.app.util.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity to browse and view contents of ZIP files on a remote device.
 * Uses the /api/zip-list and /api/zip-entry endpoints.
 */
class ZipViewerActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var contentContainer: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var contentTitle: TextView
    private lateinit var emptyView: TextView

    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var zipPath: String = ""
    private var zipName: String = ""
    private var entries: List<ZipEntryItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Content title bar
        contentTitle = TextView(this).apply {
            text = "ZIP Viewer"
            textSize = 16f
            setPadding(24, 16, 24, 8)
            visibility = View.GONE
        }
        rootLayout.addView(contentTitle)

        // Progress bar
        progressBar = ProgressBar(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.VISIBLE
        }
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 32, 0, 32)
            addView(progressBar)
        }
        rootLayout.addView(progressContainer)

        // RecyclerView for ZIP entries
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@ZipViewerActivity)
        }
        rootLayout.addView(recyclerView)

        // Content scroll (for viewing file contents)
        contentScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            )
            visibility = View.GONE
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }
        contentScroll.addView(contentContainer)
        rootLayout.addView(contentScroll)

        // Empty view
        emptyView = TextView(this).apply {
            text = "ZIP trống hoặc không thể đọc"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        rootLayout.addView(emptyView)

        setContentView(rootLayout)

        apiClient = ApiClient()

        peerHost = intent.getStringExtra("peer_host") ?: ""
        peerPort = intent.getIntExtra("peer_port", 0)
        zipPath = intent.getStringExtra("zip_path") ?: ""
        zipName = intent.getStringExtra("zip_name") ?: "ZIP Viewer"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = zipName

        loadZipContents()
    }

    override fun onSupportNavigateUp(): Boolean {
        // If viewing content, go back to list
        if (contentScroll.visibility == View.VISIBLE) {
            showEntryList()
            return true
        }
        finish()
        return true
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (contentScroll.visibility == View.VISIBLE) {
            showEntryList()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun loadZipContents() {
        val peer = PeerDevice("", peerHost, peerPort)
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val zipEntries = apiClient.listZipEntries(peer, zipPath)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE

                    if (zipEntries != null && zipEntries.isNotEmpty()) {
                        entries = zipEntries
                        recyclerView.adapter = ZipEntryAdapter(zipEntries) { entry ->
                            onEntryClick(entry)
                        }
                        recyclerView.visibility = View.VISIBLE
                        supportActionBar?.subtitle = "${zipEntries.size} mục"
                    } else {
                        emptyView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                    emptyView.text = "Lỗi đọc ZIP: ${e.message}"
                    Toast.makeText(this@ZipViewerActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onEntryClick(entry: ZipEntryItem) {
        if (entry.isDirectory) {
            Toast.makeText(this, "Thư mục: ${entry.name}", Toast.LENGTH_SHORT).show()
            return
        }

        val peer = PeerDevice("", peerHost, peerPort)
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val content = apiClient.getZipEntryContent(peer, zipPath, entry.name)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE

                    if (content != null) {
                        showEntryContent(content)
                    } else {
                        Toast.makeText(this@ZipViewerActivity, "Không thể đọc file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ZipViewerActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showEntryContent(content: com.p2pfileshare.app.model.ZipContent) {
        recyclerView.visibility = View.GONE
        contentScroll.visibility = View.VISIBLE
        contentTitle.visibility = View.VISIBLE
        contentTitle.text = content.name
        contentContainer.removeAllViews()

        if (content.type == "text") {
            val textView = TextView(this).apply {
                text = content.content
                textSize = 13f
                setPadding(8, 8, 8, 8)
                setTextIsSelectable(true)
            }
            contentContainer.addView(textView)
        } else {
            val msgView = TextView(this).apply {
                text = content.message.ifEmpty { "Xem trước không hỗ trợ cho loại file này" }
                textSize = 14f
                setPadding(8, 8, 8, 8)
            }
            contentContainer.addView(msgView)
        }

        supportActionBar?.title = content.name
    }

    private fun showEntryList() {
        contentScroll.visibility = View.GONE
        contentTitle.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        supportActionBar?.title = zipName
        supportActionBar?.subtitle = "${entries.size} mục"
    }

    // ========================
    // ZIP Entry Adapter
    // ========================

    inner class ZipEntryAdapter(
        private val items: List<ZipEntryItem>,
        private val onClick: (ZipEntryItem) -> Unit
    ) : RecyclerView.Adapter<ZipEntryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(android.R.id.icon)
            val name: TextView = view.findViewById(android.R.id.text1)
            val details: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 12, 16, 12)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
            }

            val icon = ImageView(parent.context).apply {
                id = android.R.id.icon
                setImageResource(R.drawable.ic_file)
                setColorFilter(android.graphics.Color.parseColor("#757575"))
                val lp = LinearLayout.LayoutParams(72, 72)
                lp.setMargins(0, 0, 24, 0)
                layoutParams = lp
            }
            view.addView(icon)

            val textContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#212121"))
            }
            textContainer.addView(nameView)

            val detailsView = TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
            }
            textContainer.addView(detailsView)

            view.addView(textContainer)

            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val fileName = item.name.substringAfterLast("/").ifEmpty { item.name }
            holder.name.text = fileName

            if (item.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.details.text = "Thư mục"
            } else {
                val ext = fileName.substringAfterLast(".", "").lowercase()
                holder.icon.setImageResource(
                    when {
                        ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> R.drawable.ic_image
                        ext in listOf("mp4", "avi", "mkv") -> R.drawable.ic_video
                        ext in listOf("mp3", "wav", "flac") -> R.drawable.ic_audio
                        ext == "pdf" -> R.drawable.ic_pdf
                        ext in listOf("txt", "log", "md", "json", "xml", "html", "css", "js", "csv") -> R.drawable.ic_text
                        ext == "apk" -> R.drawable.ic_apk
                        ext == "zip" -> R.drawable.ic_archive
                        else -> R.drawable.ic_file
                    }
                )
                holder.details.text = formatSize(item.size) +
                        if (item.compressedSize > 0) " (nén: ${formatSize(item.compressedSize)})" else ""
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
                else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
            }
        }
    }
}

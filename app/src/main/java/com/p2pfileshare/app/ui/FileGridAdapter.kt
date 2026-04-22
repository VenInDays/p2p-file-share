package com.p2pfileshare.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.FileItem
import java.text.SimpleDateFormat
import java.util.Locale

class FileGridAdapter(
    private val context: Context,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val onSelectionToggle: ((FileItem) -> Unit)? = null
) : RecyclerView.Adapter<FileGridAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()
    private val selectedItems = mutableSetOf<FileItem>()
    private val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())

    var isSelectionMode: Boolean = false

    fun setItems(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun setSelectedItems(selected: Set<FileItem>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_file_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.ivFileIconGrid)
        private val name: TextView = view.findViewById(R.id.tvFileNameGrid)
        private val size: TextView = view.findViewById(R.id.tvFileSizeGrid)
        private val iconBg: View = view.findViewById(R.id.viewIconBgGrid)
        private val selectionCheck: ImageView = view.findViewById(R.id.ivSelectionCheckGrid)

        fun bind(item: FileItem) {
            name.text = item.name

            if (item.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder)
                size.visibility = View.GONE
            } else {
                icon.setImageResource(getIconForFile(item))
                size.visibility = View.VISIBLE
                size.text = formatFileSize(item.size)
            }

            // Selection state
            val isSelected = selectedItems.contains(item)
            selectionCheck.visibility = if (isSelectionMode && isSelected) View.VISIBLE else View.GONE
            if (isSelectionMode && isSelected) {
                (itemView as? com.google.android.material.card.MaterialCardView)?.setStrokeColor(Color.parseColor("#2196F3"))
                (itemView as? com.google.android.material.card.MaterialCardView)?.strokeWidth = 2
            } else {
                (itemView as? com.google.android.material.card.MaterialCardView)?.strokeWidth = 0
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    onSelectionToggle?.invoke(item)
                } else {
                    onItemClick(item)
                }
            }
            itemView.setOnLongClickListener {
                if (isSelectionMode) {
                    onSelectionToggle?.invoke(item)
                } else {
                    onItemLongClick(item)
                }
                true
            }
        }

        private fun getIconForFile(item: FileItem): Int {
            val name = item.name.lowercase()
            return when {
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
}

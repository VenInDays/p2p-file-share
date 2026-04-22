package com.p2pfileshare.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.p2pfileshare.app.App
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.FileItem
import com.p2pfileshare.app.model.PeerDevice
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class FileListAdapter(
    private val context: Context,
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    var currentPeer: PeerDevice? = null

    fun setItems(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.ivIcon)
        private val thumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        private val name: TextView = view.findViewById(R.id.tvName)
        private val details: TextView = view.findViewById(R.id.tvDetails)
        private val size: TextView = view.findViewById(R.id.tvSize)
        private val playOverlay: View = view.findViewById(R.id.playOverlay)

        fun bind(item: FileItem) {
            name.text = item.name

            if (item.isDirectory) {
                icon.visibility = View.VISIBLE
                thumbnail.visibility = View.GONE
                playOverlay.visibility = View.GONE
                icon.setImageResource(R.drawable.ic_folder)
                details.text = dateFormat.format(item.lastModified)
                size.visibility = View.GONE
            } else {
                val isImage = FilePreviewActivity.isImageFile(item.name)
                val isVideo = FilePreviewActivity.isVideoFile(item.name)

                if (isImage || isVideo) {
                    // Load thumbnail from remote peer
                    icon.visibility = View.GONE
                    thumbnail.visibility = View.VISIBLE
                    playOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

                    val peer = currentPeer
                    if (peer != null) {
                        val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                        val url = "http://${peer.host}:${peer.port}/api/download?path=$encodedPath"
                        thumbnail.load(url) {
                            crossfade(true)
                            size(200, 200)
                            error(getIconResource(item))
                            listener(onError = { _, _ ->
                                // Fallback to icon on error
                                thumbnail.visibility = View.GONE
                                icon.visibility = View.VISIBLE
                                icon.setImageResource(getIconResource(item))
                            })
                        }
                    } else {
                        icon.visibility = View.VISIBLE
                        thumbnail.visibility = View.GONE
                        playOverlay.visibility = View.GONE
                        icon.setImageResource(getIconResource(item))
                    }
                } else {
                    icon.visibility = View.VISIBLE
                    thumbnail.visibility = View.GONE
                    playOverlay.visibility = View.GONE
                    icon.setImageResource(getIconResource(item))
                }

                details.text = dateFormat.format(item.lastModified)
                size.visibility = View.VISIBLE
                size.text = formatFileSize(item.size)
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        private fun getIconResource(item: FileItem): Int {
            val name = item.name.lowercase()
            return when {
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp") -> R.drawable.ic_image
                name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv") ||
                name.endsWith(".3gp") || name.endsWith(".webm") || name.endsWith(".mov") -> R.drawable.ic_video
                name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") ||
                name.endsWith(".ogg") || name.endsWith(".aac") -> R.drawable.ic_audio
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

package com.p2pfileshare.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.p2pfileshare.app.R
import com.p2pfileshare.app.model.PeerDevice

class PeerAdapter(
    private val context: Context,
    private val onItemClick: (PeerDevice) -> Unit
) : RecyclerView.Adapter<PeerAdapter.ViewHolder>() {

    private val peers = mutableListOf<PeerDevice>()

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_peer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(peers[position])
    }

    override fun getItemCount() = peers.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvPeerName)
        private val address: TextView = view.findViewById(R.id.tvPeerAddress)

        fun bind(peer: PeerDevice) {
            name.text = peer.name
            address.text = "${peer.host}:${peer.port}"
            itemView.setOnClickListener { onItemClick(peer) }
        }
    }
}

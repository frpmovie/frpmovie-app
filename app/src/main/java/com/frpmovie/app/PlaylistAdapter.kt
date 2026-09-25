package com.frpmovie.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val items: List<PlayerPlaylist.Item>,
    private val currentUrl: String,
    private val onClick: (PlayerPlaylist.Item) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    inner class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.tvPlaylistName.text = item.name
        val isCurrent = item.url == currentUrl
        holder.binding.tvPlaylistName.setTextColor(
            ContextCompat.getColor(context, if (isCurrent) R.color.brand else R.color.ink)
        )
        if (item.logo.startsWith("http")) {
            Glide.with(holder.itemView)
                .load(item.logo)
                .placeholder(R.drawable.logo_placeholder)
                .error(R.drawable.logo_placeholder)
                .into(holder.binding.ivPlaylistLogo)
        } else {
            Glide.with(holder.itemView).clear(holder.binding.ivPlaylistLogo)
            holder.binding.ivPlaylistLogo.setImageResource(R.drawable.logo_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}

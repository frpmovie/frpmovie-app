package com.frpmovie.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val items: List<PlayerPlaylist.Item>,
    private val currentUrl: String,
    private val isChannels: Boolean,
    private val onClick: (PlayerPlaylist.Item) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    inner class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.playlistThumbFrame.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val density = holder.itemView.resources.displayMetrics.density
        b.tvPlaylistName.text = item.name
        b.tvPlaylistNumber.text = (position + 1).toString()
        b.tvPlaylistNumber.visibility = if (isChannels) View.VISIBLE else View.GONE

        val isCurrent = item.url == currentUrl
        holder.itemView.isSelected = isCurrent
        b.tvPlaylistNow.visibility = if (isCurrent) View.VISIBLE else View.GONE

        // Logos de canales: completos sobre fondo; capturas de episodios: a sangre.
        b.ivPlaylistLogo.scaleType = if (isChannels) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val pad = if (isChannels) (4 * density).toInt() else 0
        b.ivPlaylistLogo.setPadding(pad, pad, pad, pad)
        if (item.logo.startsWith("http")) {
            Glide.with(holder.itemView)
                .load(item.logo)
                .placeholder(R.drawable.logo_placeholder)
                .error(R.drawable.logo_placeholder)
                .into(b.ivPlaylistLogo)
        } else {
            Glide.with(holder.itemView).clear(b.ivPlaylistLogo)
            b.ivPlaylistLogo.setImageResource(R.drawable.logo_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}

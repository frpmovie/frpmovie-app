package com.frpmovie.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemEpisodeBinding

data class Episode(
    val id: Int,
    val episodeNum: Int,
    val title: String,
    val ext: String,
    val duration: String,
    val plot: String,
    val image: String
)

class EpisodeAdapter(
    private var items: List<Episode>,
    private val onClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    inner class VH(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.binding.tvEpisodeTitle.text = "${ep.episodeNum}. ${ep.title.ifBlank { "Episodio ${ep.episodeNum}" }}"
        holder.binding.tvEpisodeMeta.text = if (ep.duration.isNotBlank()) ep.duration else ep.plot.take(80)
        holder.binding.tvEpisodeMeta.visibility =
            if (holder.binding.tvEpisodeMeta.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        if (ep.image.startsWith("http")) {
            Glide.with(holder.itemView)
                .load(ep.image)
                .placeholder(R.drawable.logo_placeholder)
                .error(R.drawable.logo_placeholder)
                .into(holder.binding.ivEpisode)
        } else {
            Glide.with(holder.itemView).clear(holder.binding.ivEpisode)
            holder.binding.ivEpisode.setImageResource(R.drawable.logo_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(ep) }
        holder.itemView.isFocusable = true
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) 0xFF1E2741.toInt() else 0x00000000)
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<Episode>) {
        items = newItems
        notifyDataSetChanged()
    }
}

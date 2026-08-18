package com.frpmovie.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemChannelBinding

class ChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    inner class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.binding.tvName.text = ch.name
        holder.binding.ivLogo.visibility = View.VISIBLE
        if (ch.logo.startsWith("http")) {
            Glide.with(holder.itemView)
                .load(ch.logo)
                .placeholder(R.drawable.logo_placeholder)
                .error(R.drawable.logo_placeholder)
                .into(holder.binding.ivLogo)
        } else {
            Glide.with(holder.itemView).clear(holder.binding.ivLogo)
            holder.binding.ivLogo.setImageResource(R.drawable.logo_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(ch) }
        // Para Android TV: resaltar al enfocar
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.08f else 1f
            v.scaleY = if (hasFocus) 1.08f else 1f
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }
}

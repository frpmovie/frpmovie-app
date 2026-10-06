package com.frpmovie.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemCardBinding

// Tarjetas de una fila horizontal del inicio: logos apaisados para canales,
// carátulas verticales para películas/series.
class CardAdapter(
    private val items: List<Channel>,
    private val isPoster: Boolean,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    inner class VH(val binding: ItemCardBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.cardImageFrame.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val density = holder.itemView.resources.displayMetrics.density
        val width = ((if (isPoster) 112 else 148) * density).toInt()
        val height = ((if (isPoster) 168 else 88) * density).toInt()

        val frameParams = b.cardImageFrame.layoutParams
        frameParams.width = width
        frameParams.height = height
        b.cardImageFrame.layoutParams = frameParams
        val nameParams = b.tvCardName.layoutParams
        nameParams.width = width
        b.tvCardName.layoutParams = nameParams

        b.tvCardName.text = item.name
        b.ivCard.scaleType = if (isPoster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val pad = if (isPoster) 0 else (12 * density).toInt()
        b.ivCard.setPadding(pad, pad, pad, pad)

        if (item.logo.startsWith("http")) {
            Glide.with(holder.itemView)
                .load(item.logo)
                .placeholder(R.drawable.logo_placeholder)
                .error(R.drawable.logo_placeholder)
                .into(b.ivCard)
        } else {
            Glide.with(holder.itemView).clear(b.ivCard)
            b.ivCard.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.ivCard.setImageResource(R.drawable.logo_placeholder)
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
        }
    }

    override fun getItemCount() = items.size
}

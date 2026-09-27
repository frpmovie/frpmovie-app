package com.frpmovie.app

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.frpmovie.app.databinding.ItemHeroBinding
import com.frpmovie.app.databinding.ItemRowBinding

sealed class HomeRow {
    data class Hero(val item: Channel, val actionLabel: String) : HomeRow()
    data class Items(val title: String, val categoryId: String?, val items: List<Channel>) : HomeRow()
}

// Inicio estilo Netflix: un destacado arriba y una fila horizontal por categoría.
class RowAdapter(
    private val onItemClick: (Channel) -> Unit,
    private val onSeeAll: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    private var rows: List<HomeRow> = emptyList()
    private var isPoster = false
    // Las filas comparten las vistas de tarjetas recicladas entre sí.
    private val cardPool = RecyclerView.RecycledViewPool()

    inner class HeroVH(val binding: ItemHeroBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.heroPosterFrame.clipToOutline = true
            // Fondo difuminado (Android 12+); en versiones anteriores queda
            // solo atenuado por el degradado.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.ivHeroBackdrop.setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP)
                )
            }
        }
    }

    inner class RowVH(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            val lm = LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            lm.initialPrefetchItemCount = 5
            binding.recyclerRowItems.layoutManager = lm
            binding.recyclerRowItems.setRecycledViewPool(cardPool)
        }
    }

    fun submit(newRows: List<HomeRow>, poster: Boolean) {
        rows = newRows
        isPoster = poster
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is HomeRow.Hero -> TYPE_HERO
        is HomeRow.Items -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) {
            HeroVH(ItemHeroBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Hero -> bindHero(holder as HeroVH, row)
            is HomeRow.Items -> bindRow(holder as RowVH, row)
        }
    }

    private fun bindHero(holder: HeroVH, row: HomeRow.Hero) {
        val b = holder.binding
        b.tvHeroTitle.text = row.item.name
        b.tvHeroAction.text = row.actionLabel
        Glide.with(holder.itemView).load(row.item.logo).into(b.ivHeroBackdrop)
        Glide.with(holder.itemView)
            .load(row.item.logo)
            .placeholder(R.drawable.logo_placeholder)
            .error(R.drawable.logo_placeholder)
            .into(b.ivHeroPoster)
        b.btnHeroPlay.setOnClickListener { onItemClick(row.item) }
        b.heroPosterFrame.setOnClickListener { onItemClick(row.item) }
    }

    private fun bindRow(holder: RowVH, row: HomeRow.Items) {
        val b = holder.binding
        b.tvRowTitle.text = row.title
        val catId = row.categoryId
        if (catId != null) {
            b.tvSeeAll.visibility = View.VISIBLE
            b.tvSeeAll.setOnClickListener { onSeeAll(catId) }
        } else {
            b.tvSeeAll.visibility = View.GONE
            b.tvSeeAll.setOnClickListener(null)
        }
        b.recyclerRowItems.adapter = CardAdapter(row.items, isPoster, onItemClick)
    }

    override fun getItemCount() = rows.size
}

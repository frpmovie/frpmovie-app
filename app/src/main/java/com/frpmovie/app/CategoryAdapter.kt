package com.frpmovie.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class Category(
    val id: String,
    val name: String,
    val count: Int
)

class CategoryAdapter(
    private val items: MutableList<Category>,
    private val onClick: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var selectedId: String = "all"

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        val context = holder.text.context
        holder.text.text = "${cat.name}  ·  ${cat.count}"
        val selected = cat.id == selectedId
        holder.text.backgroundTintList = if (selected)
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand))
        else
            null
        holder.text.setTextColor(
            ContextCompat.getColor(context, if (selected) R.color.ink else R.color.muted)
        )
        holder.itemView.setOnClickListener {
            selectedId = cat.id
            notifyDataSetChanged()
            onClick(cat)
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<Category>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelected(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }
}

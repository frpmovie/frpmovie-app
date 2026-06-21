package com.frpmovie.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        holder.text.text = "${cat.name}  (${cat.count})"
        holder.text.textSize = 14f
        holder.text.setPadding(40, 30, 40, 30)
        // Resaltar la categoria seleccionada
        if (cat.id == selectedId) {
            holder.text.setBackgroundColor(0xFF6366F1.toInt())
            holder.text.setTextColor(0xFFFFFFFF.toInt())
        } else {
            holder.text.setBackgroundColor(0x00000000)
            holder.text.setTextColor(0xFFCCCCCC.toInt())
        }
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

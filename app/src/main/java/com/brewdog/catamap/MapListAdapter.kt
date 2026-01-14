package com.brewdog.catamap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adaptateur pour afficher les cartes groupées par catégories
 */
class MapListAdapter(
    private val onMapClick: (MapItem) -> Unit,
    private val onEditClick: (MapItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    companion object {
        private const val VIEW_TYPE_CATEGORY = 0
        private const val VIEW_TYPE_MAP = 1
    }

    /**
     * Classe de base pour les éléments de la liste
     */
    sealed class ListItem {
        data class CategoryHeader(val category: Category) : ListItem()
        data class MapEntry(val map: MapItem) : ListItem()
    }

    /**
     * Met à jour la liste avec les nouvelles données
     */
    fun updateData(database: MapDatabase) {
        items.clear()

        // Trier les catégories : "Sans catégorie" en premier, puis par nom
        val sortedCategories = database.categories.sortedWith(
            compareBy<Category> { !it.isSystem }.thenBy { it.name }
        )

        for (category in sortedCategories) {
            val maps = database.getMapsByCategory(category.id)
            if (maps.isNotEmpty()) {
                items.add(ListItem.CategoryHeader(category))
                maps.forEach { items.add(ListItem.MapEntry(it)) }
            }
        }

        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.CategoryHeader -> VIEW_TYPE_CATEGORY
            is ListItem.MapEntry -> VIEW_TYPE_MAP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_CATEGORY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_category_header, parent, false)
                CategoryViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_map_list, parent, false)
                MapViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.CategoryHeader -> {
                (holder as CategoryViewHolder).bind(item.category)
            }
            is ListItem.MapEntry -> {
                (holder as MapViewHolder).bind(item.map)
            }
        }
    }

    override fun getItemCount() = items.size

    /**
     * ViewHolder pour les en-têtes de catégorie
     */
    class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val categoryName: TextView = view.findViewById(R.id.categoryName)

        fun bind(category: Category) {
            categoryName.text = category.name.uppercase()
        }
    }

    /**
     * ViewHolder pour les cartes
     */
    inner class MapViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val mapName: TextView = view.findViewById(R.id.mapName)
        private val defaultIndicator: TextView = view.findViewById(R.id.defaultIndicator)
        private val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)

        fun bind(map: MapItem) {
            mapName.text = map.name
            defaultIndicator.visibility = if (map.isDefault) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onMapClick(map) }
            btnEdit.setOnClickListener { onEditClick(map) }
        }
    }
}
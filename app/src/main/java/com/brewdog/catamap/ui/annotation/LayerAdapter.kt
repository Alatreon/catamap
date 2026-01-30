package com.brewdog.catamap.ui.annotation

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.brewdog.catamap.R
import com.brewdog.catamap.domain.annotation.models.Layer
import com.brewdog.catamap.domain.annotation.models.LayerType
import com.brewdog.catamap.utils.logging.Logger

/**
 * Adapter pour afficher la liste des calques
 */
class LayerAdapter(
    private val onLayerClick: (Layer) -> Unit,
    private val onToggleVisibility: (Layer) -> Unit,
    private val onDelete: (Layer) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onDoubleClick: (Layer) -> Unit
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

    companion object {
        private const val TAG = "LayerAdapter"
        private const val DOUBLE_CLICK_DELAY_MS = 300L
    }

    private val layers = mutableListOf<Layer>()
    private var activeLayerId: String? = null

    /**
     * Met à jour la liste des calques
     */
    fun updateLayers(newLayers: List<Layer>, activeId: String?) {
        Logger.entry(TAG, "updateLayers", "count=${newLayers.size}")

        layers.clear()
        layers.addAll(newLayers)
        activeLayerId = activeId

        notifyDataSetChanged()

        Logger.d(TAG, "Layers updated: ${layers.map { it.name }}")
    }

    /**
     * Récupère la liste actuelle des calques
     */
    fun getLayers(): List<Layer> = layers.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layer, parent, false)
        return LayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
        holder.bind(layers[position])
    }

    override fun getItemCount(): Int = layers.size

    /**
     * ViewHolder pour un calque
     */
    inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private val activeIndicator: View = itemView.findViewById(R.id.activeIndicator)
        private val layerTypeIcon: ImageView = itemView.findViewById(R.id.layerTypeIcon)
        private val layerName: TextView = itemView.findViewById(R.id.layerName)
        private val btnToggleVisibility: ImageButton = itemView.findViewById(R.id.btnToggleVisibility)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        private var lastClickTime = 0L

        fun bind(layer: Layer) {
            Logger.v(TAG, "Binding layer: ${layer.name}")

            // Nom
            layerName.text = layer.name

            // Indicateur actif
            val isActive = layer.id == activeLayerId
            activeIndicator.visibility = if (isActive) View.VISIBLE else View.GONE

            // Style si actif
            layerName.alpha = if (isActive) 1.0f else 0.7f

            // Icône type de calque
            layerTypeIcon.setImageResource(getLayerTypeIcon(layer))

            // Icône visibilité
            btnToggleVisibility.setImageResource(
                if (layer.isVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
            btnToggleVisibility.alpha = if (layer.isVisible) 1.0f else 0.5f

            // Grisage si masqué
            if (!layer.isVisible) {
                layerName.alpha = 0.5f
                layerTypeIcon.alpha = 0.5f
            } else {
                layerTypeIcon.alpha = 1.0f
            }

            // Click sur la ligne = activation (sauf si masqué)
            itemView.setOnClickListener {
                if (layer.isVisible) {
                    handleClick(layer)
                } else {
                    Logger.d(TAG, "Cannot activate hidden layer: ${layer.name}")
                }
            }

            // Toggle visibilité
            btnToggleVisibility.setOnClickListener {
                // Pas besoin de stopPropagation, le click ne se propage pas au parent automatiquement
                Logger.d(TAG, "Toggle visibility: ${layer.name}")
                onToggleVisibility(layer)
            }

            // Suppression
            btnDelete.setOnClickListener {
                Logger.d(TAG, "Delete clicked: ${layer.name}")
                onDelete(layer)
            }

            // Drag handle
            dragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Logger.v(TAG, "Drag started: ${layer.name}")
                    view.performClick() // Pour l'accessibilité
                    onStartDrag(this)
                    return@setOnTouchListener true // Consommer l'événement
                }
                false
            }
        }

        /**
         * Gère le clic simple/double
         */
        private fun handleClick(layer: Layer) {
            val now = System.currentTimeMillis()
            val timeSinceLastClick = now - lastClickTime

            if (timeSinceLastClick < DOUBLE_CLICK_DELAY_MS) {
                // Double-clic détecté
                Logger.d(TAG, "Double-click: ${layer.name}")
                onDoubleClick(layer)
                lastClickTime = 0L // Reset
            } else {
                // Simple clic
                Logger.d(TAG, "Single click: ${layer.name}")
                onLayerClick(layer)
                lastClickTime = now
            }
        }

        /**
         * Détermine l'icône selon le type de calque
         */
        private fun getLayerTypeIcon(layer: Layer): Int {
            return when (layer.getLayerType()) {
                LayerType.EMPTY -> R.drawable.ic_layer_empty
                LayerType.TEXT_ONLY -> R.drawable.ic_text_fields
                LayerType.DRAWING_ONLY -> R.drawable.ic_brush
                LayerType.MIXED -> R.drawable.ic_layers
            }
        }
    }
}
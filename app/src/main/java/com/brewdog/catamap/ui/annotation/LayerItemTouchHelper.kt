package com.brewdog.catamap.ui.annotation

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.brewdog.catamap.utils.logging.Logger

/**
 * ItemTouchHelper.Callback pour gérer le drag & drop des calques
 *
 * Features :
 * - Drag vertical uniquement (UP/DOWN)
 * - Pas de swipe
 * - Déclenchement via drag handle uniquement
 */
class LayerItemTouchHelper(
    private val onMove: (fromPosition: Int, toPosition: Int) -> Unit
) : ItemTouchHelper.Callback() {

    companion object {
        private const val TAG = "LayerItemTouchHelper"
    }

    /**
     * Définit les directions de mouvement autorisées
     * - Drag : UP | DOWN
     * - Swipe : Aucun
     */
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = 0 // Pas de swipe

        return makeMovementFlags(dragFlags, swipeFlags)
    }

    /**
     * Désactive le long press drag
     * Le drag est déclenché uniquement via le drag handle
     */
    override fun isLongPressDragEnabled(): Boolean = false

    /**
     * Désactive le swipe
     */
    override fun isItemViewSwipeEnabled(): Boolean = false

    /**
     * Callback quand un item est déplacé
     */
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPosition = viewHolder.bindingAdapterPosition
        val toPosition = target.bindingAdapterPosition

        Logger.v(TAG, "onMove: $fromPosition → $toPosition")

        // Notifier le callback
        onMove(fromPosition, toPosition)

        return true
    }

    /**
     * Pas de swipe implémenté
     */
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Pas utilisé
    }

    /**
     * Change l'apparence de l'item pendant le drag
     */
    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)

        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            // Élever l'item pendant le drag
            viewHolder?.itemView?.alpha = 0.7f
            viewHolder?.itemView?.elevation = 8f

            Logger.v(TAG, "Drag started")
        }
    }

    /**
     * Restaure l'apparence quand le drag est terminé
     */
    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)

        // Restaurer l'apparence normale
        viewHolder.itemView.alpha = 1.0f
        viewHolder.itemView.elevation = 0f

        Logger.v(TAG, "Drag ended")
    }
}
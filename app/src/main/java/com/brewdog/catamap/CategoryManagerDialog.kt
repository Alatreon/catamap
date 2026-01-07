package com.brewdog.catamap

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

/**
 * Modale de gestion des catégories
 */
class CategoryManagerDialog : DialogFragment() {

    private lateinit var database: MapDatabase
    private var onChangeListener: (() -> Unit)? = null

    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var adapter: CategoryAdapter

    companion object {
        fun newInstance(database: MapDatabase): CategoryManagerDialog {
            return CategoryManagerDialog().apply {
                this.database = database
            }
        }
    }

    fun setOnChangeListener(listener: () -> Unit) {
        onChangeListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(
            R.layout.dialog_category_manager,
            null
        )
        categoryRecyclerView = view.findViewById(R.id.categoryRecyclerView)
        val btnAddCategory = view.findViewById<Button>(R.id.btnAddCategory)
        val btnClose = view.findViewById<Button>(R.id.btnClose)

        // Setup RecyclerView
        adapter = CategoryAdapter(
            categories = database.categories,
            onDelete = { category -> deleteCategory(category) }
        )

        categoryRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        categoryRecyclerView.adapter = adapter

        // Listeners
        btnAddCategory.setOnClickListener { showAddCategoryDialog() }
        btnClose.setOnClickListener { dismiss() }

        return AlertDialog.Builder(requireContext(), R.style.Theme_CataMap_Dialog)
            .setView(view)
            .create()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(requireContext())
        input.hint = "Nom de la catégorie"

        AlertDialog.Builder(requireContext(), R.style.Theme_CataMap_Dialog)
            .setTitle("Nouvelle catégorie")
            .setView(input)
            .setPositiveButton("Ajouter") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val category = Category(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        isSystem = false
                    )
                    database.addOrUpdateCategory(category)
                    adapter.notifyDataSetChanged()
                    onChangeListener?.invoke()
                } else {
                    Toast.makeText(requireContext(), "Nom invalide", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteCategory(category: Category) {
        if (category.isSystem) {
            Toast.makeText(
                requireContext(),
                "La catégorie \"Sans catégorie\" ne peut pas être supprimée",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(requireContext(), R.style.Theme_CataMap_Dialog)
            .setTitle("Supprimer la catégorie")
            .setMessage("Toutes les cartes de cette catégorie seront déplacées vers \"Sans catégorie\". Continuer ?")
            .setPositiveButton("Supprimer") { _, _ ->
                if (database.removeCategory(category.id)) {
                    adapter.notifyDataSetChanged()
                    onChangeListener?.invoke()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Adaptateur pour la liste des catégories
     */
    private class CategoryAdapter(
        private val categories: List<Category>,
        private val onDelete: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(categories[position])
        }

        override fun getItemCount() = categories.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val categoryName: TextView = view.findViewById(R.id.categoryName)
            private val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteCategory)

            fun bind(category: Category) {
                categoryName.text = category.name

                // Désactiver le bouton supprimer pour "Sans catégorie"
                if (category.isSystem) {
                    btnDelete.isEnabled = false
                    btnDelete.alpha = 0.3f
                } else {
                    btnDelete.isEnabled = true
                    btnDelete.alpha = 1.0f
                    btnDelete.setOnClickListener { onDelete(category) }
                }
            }
        }
    }
}
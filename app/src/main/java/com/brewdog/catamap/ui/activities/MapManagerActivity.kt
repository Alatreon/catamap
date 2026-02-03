package com.brewdog.catamap.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brewdog.catamap.R
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.models.MapDatabase
import com.brewdog.catamap.data.repository.MapRepository
import com.brewdog.catamap.ui.adapters.MapListAdapter
import com.brewdog.catamap.ui.dialogs.AddEditMapDialog
import com.brewdog.catamap.ui.dialogs.CategoryManagerDialog
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.utils.logging.Logger

/**
 * Activité de gestion des cartes en plein écran
 */

/**
 * Activité de gestion des cartes en plein écran
 */
class MapManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MapManagerActivity"
    }
    private lateinit var repository: MapRepository
    private lateinit var database: MapDatabase
    private lateinit var adapter: MapListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyMessage: TextView
    private lateinit var btnAddMap: Button
    private lateinit var btnManageCategories: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.entry(TAG, "onCreate")

        setContentView(R.layout.activity_map_manager)

        repository = MapRepository(this)
        database = repository.loadDatabase()

        // Setup Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Vues
        recyclerView = findViewById(R.id.mapRecyclerView)
        emptyMessage = findViewById(R.id.emptyMessage)
        btnAddMap = findViewById(R.id.btnAddMap)
        btnManageCategories = findViewById(R.id.btnManageCategories)

        // Setup RecyclerView
        adapter = MapListAdapter(
            onMapClick = { map -> onMapSelected(map) },
            onEditClick = { map -> openEditMapDialog(map) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Boutons
        btnAddMap.setOnClickListener { openAddMapDialog() }
        btnManageCategories.setOnClickListener { openCategoryManagerDialog() }

        // Afficher les données
        refreshList()

        Logger.exit(TAG, "onCreate")
    }

    /**
     * Rafraîchit la liste des cartes
     */
    private fun refreshList() {
        Logger.entry(TAG, "refreshList")

        adapter.updateData(database)

        // Afficher/masquer le message vide
        emptyMessage.visibility = if (database.maps.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (database.maps.isEmpty()) View.GONE else View.VISIBLE

        Logger.exit(TAG, "refreshList")
    }

    /**
     * Ouvre la modale d'ajout de carte
     */
    private fun openAddMapDialog() {
        Logger.entry(TAG, "openAddMapDialog")

        val dialog = AddEditMapDialog.newInstance(mode = AddEditMapDialog.Mode.ADD)
        dialog.setOnSaveListener { map ->
            database.addOrUpdateMap(map)
            repository.saveDatabase(database)
            refreshList()
        }
        dialog.show(supportFragmentManager, "AddMapDialog")

        Logger.exit(TAG, "openAddMapDialog")
    }

    /**
     * Ouvre la modale de modification de carte
     */
    private fun openEditMapDialog(map: MapItem) {
        Logger.entry(TAG, "openEditMapDialog", map.id, map.name)

        val dialog = AddEditMapDialog.newInstance(
            mode = AddEditMapDialog.Mode.EDIT,
            existingMap = map
        )

        dialog.setOnSaveListener { updatedMap ->
            database.addOrUpdateMap(updatedMap)
            repository.saveDatabase(database)
            refreshList()
        }

        dialog.setOnDeleteListener {
            if (database.removeMap(map.id)) {
                repository.saveDatabase(database)
                refreshList()
            }
        }

        dialog.show(supportFragmentManager, "EditMapDialog")

        Logger.exit(TAG, "openEditMapDialog")
    }

    /**
     * Ouvre la modale de gestion des catégories
     */
    private fun openCategoryManagerDialog() {
        Logger.entry(TAG, "openCategoryManagerDialog")

        val dialog = CategoryManagerDialog.newInstance(database)
        dialog.setOnChangeListener {
            repository.saveDatabase(database)
            refreshList()
        }
        dialog.show(supportFragmentManager, "CategoryManagerDialog")

        Logger.exit(TAG, "openCategoryManagerDialog")
    }

    /**
     * Sélectionne une carte et retourne à MainActivity
     */
    private fun onMapSelected(map: MapItem) {
        Logger.entry(TAG, "onMapSelected", map.id, map.name)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(AppConstants.Intent.EXTRA_SELECTED_MAP_ID, map.id)
            // Flags pour revenir à MainActivity existante sans la recréer
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish() // Fermer le gestionnaire de cartes

        Logger.exit(TAG, "onMapSelected")
    }

    override fun onPause() {
        super.onPause()
        Logger.entry(TAG, "onPause")

        repository.saveDatabase(database)

        Logger.exit(TAG, "onPause")
    }
}
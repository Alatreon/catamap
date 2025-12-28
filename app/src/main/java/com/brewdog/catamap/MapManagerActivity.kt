package com.brewdog.catamap

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Activité de gestion des cartes en plein écran
 */
class MapManagerActivity : AppCompatActivity() {

    private lateinit var storage: MapStorage
    private lateinit var database: MapDatabase
    private lateinit var adapter: MapListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyMessage: TextView
    private lateinit var btnAddMap: Button
    private lateinit var btnManageCategories: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_manager)

        // Initialisation
        storage = MapStorage(this)
        database = storage.load()

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
    }

    /**
     * Rafraîchit la liste des cartes
     */
    private fun refreshList() {
        adapter.updateData(database)

        // Afficher/masquer le message vide
        emptyMessage.visibility = if (database.maps.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (database.maps.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Ouvre la modale d'ajout de carte
     */
    private fun openAddMapDialog() {
        val dialog = AddEditMapDialog.newInstance(mode = AddEditMapDialog.Mode.ADD)
        dialog.setOnSaveListener { map ->
            database.addOrUpdateMap(map)
            storage.save(database)
            refreshList()
        }
        dialog.show(supportFragmentManager, "AddMapDialog")
    }

    /**
     * Ouvre la modale de modification de carte
     */
    private fun openEditMapDialog(map: MapItem) {
        val dialog = AddEditMapDialog.newInstance(
            mode = AddEditMapDialog.Mode.EDIT,
            existingMap = map
        )

        dialog.setOnSaveListener { updatedMap ->
            database.addOrUpdateMap(updatedMap)
            storage.save(database)
            refreshList()
        }

        dialog.setOnDeleteListener {
            if (database.removeMap(map.id)) {
                storage.save(database)
                refreshList()
            }
        }

        dialog.show(supportFragmentManager, "EditMapDialog")
    }

    /**
     * Ouvre la modale de gestion des catégories
     */
    private fun openCategoryManagerDialog() {
        val dialog = CategoryManagerDialog.newInstance(database)
        dialog.setOnChangeListener {
            storage.save(database)
            refreshList()
        }
        dialog.show(supportFragmentManager, "CategoryManagerDialog")
    }

    /**
     * Sélectionne une carte et retourne à MainActivity
     */
    private fun onMapSelected(map: MapItem) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SELECTED_MAP_ID, map.id)
            // Flags pour revenir à MainActivity existante sans la recréer
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish() // Fermer le gestionnaire de cartes
    }

    override fun onPause() {
        super.onPause()
        storage.save(database)
    }
}
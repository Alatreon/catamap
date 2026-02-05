package com.brewdog.catamap.ui.activities

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt

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
    private lateinit var btnAbout: Button

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
        btnAbout = findViewById(R.id.btnAbout)

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
        btnAbout.setOnClickListener { showAboutDialog() }

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
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()

        Logger.exit(TAG, "onMapSelected")
    }

    /**
     * Affiche le dialog "À propos" avec fond sombre et liens cliquables
     */
    private fun showAboutDialog() {
        Logger.entry(TAG, "showAboutDialog")

        // Message complet
        val fullMessage = "Merci d'utiliser CataMap 🤍\n\n" +
                "Pour toute suggestion ou rapport de bug :\n" +
                "catamapbrewdog@gmail.com\n\n" +
                "Pour tout soutien :\n" +
                "https://ko-fi.com/catamap"

        // Créer un SpannableString pour les liens cliquables
        val spannableMessage = SpannableString(fullMessage)

        // Email cliquable
        val emailStart = fullMessage.indexOf("catamapbrewdog@gmail.com")
        val emailEnd = emailStart + "catamapbrewdog@gmail.com".length

        val emailClickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                openEmail()
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = "#FF9800".toColorInt() // Orange
                ds.isUnderlineText = true
            }
        }
        spannableMessage.setSpan(emailClickable, emailStart, emailEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Lien Ko-fi cliquable
        val kofiStart = fullMessage.indexOf("https://ko-fi.com/catamap")
        val kofiEnd = kofiStart + "https://ko-fi.com/catamap".length

        val kofiClickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                openKofiLink()
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = "#FF9800".toColorInt() // Orange
                ds.isUnderlineText = true
            }
        }
        spannableMessage.setSpan(kofiClickable, kofiStart, kofiEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Créer et afficher le dialog avec style sombre
        val dialog = AlertDialog.Builder(this, R.style.AboutDialog)
            .setTitle(R.string.about_dialog_title)
            .setMessage(spannableMessage)
            .setPositiveButton(R.string.about_dialog_close, null)
            .create()

        dialog.show()

        // Personnaliser les couleurs après affichage
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor("#E0E0E0".toColorInt()) // Texte clair
            setLinkTextColor("#FF9800".toColorInt()) // Liens orange
        }

        // Couleur du titre
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            setTextColor("#E0E0E0".toColorInt())
        }

        // Couleur du bouton
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor("#FF9800".toColorInt())
        }

        Logger.i(TAG, "About dialog shown with dark theme")
        Logger.exit(TAG, "showAboutDialog")
    }

    /**
     * Ouvre l'application email avec l'adresse pré-remplie
     */
    private fun openEmail() {
        Logger.entry(TAG, "openEmail")

        try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:catamapbrewdog@gmail.com".toUri()
                putExtra(Intent.EXTRA_SUBJECT, "CataMap - Suggestion/Bug")
            }
            startActivity(Intent.createChooser(emailIntent, "Envoyer un email"))
            Logger.i(TAG, "Email app opened")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open email app", e)
        }

        Logger.exit(TAG, "openEmail")
    }

    /**
     * Ouvre le lien Ko-fi dans le navigateur
     */
    private fun openKofiLink() {
        Logger.entry(TAG, "openKofiLink")

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/catamap"))
            startActivity(browserIntent)
            Logger.i(TAG, "Ko-fi link opened in browser")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open browser", e)
        }

        Logger.exit(TAG, "openKofiLink")
    }

    override fun onPause() {
        super.onPause()
        Logger.entry(TAG, "onPause")

        repository.saveDatabase(database)

        Logger.exit(TAG, "onPause")
    }
}
package com.brewdog.catamap

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Modale d'ajout ou modification d'une carte avec onglets Light/Dark
 */
class AddEditMapDialog : DialogFragment() {

    enum class Mode { ADD, EDIT }

    private lateinit var mode: Mode
    private var existingMap: MapItem? = null
    private var onSaveListener: ((MapItem) -> Unit)? = null
    private var onDeleteListener: (() -> Unit)? = null

    private var selectedLightUri: Uri? = null
    private var selectedDarkUri: Uri? = null

    // Vues
    private lateinit var editMapName: TextInputEditText
    private lateinit var spinnerCategory: Spinner
    private lateinit var textDateAdded: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var lightModeContent: LinearLayout
    private lateinit var darkModeContent: LinearLayout
    private lateinit var textLightImagePath: TextView
    private lateinit var textDarkImagePath: TextView
    private lateinit var btnSelectLightImage: Button
    private lateinit var btnSelectDarkImage: Button
    private lateinit var checkDefaultMap: CheckBox
    private lateinit var btnDelete: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button

    // Lanceurs pour sélection d'images
    private val selectLightImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedLightUri = it
            textLightImagePath.text = it.lastPathSegment ?: it.toString()
        }
    }

    private val selectDarkImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedDarkUri = it
            textDarkImagePath.text = it.lastPathSegment ?: it.toString()
        }
    }

    companion object {
        private const val ARG_MODE = "mode"

        fun newInstance(mode: Mode, existingMap: MapItem? = null): AddEditMapDialog {
            return AddEditMapDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode.name)
                }
                this.mode = mode
                this.existingMap = existingMap
            }
        }
    }

    fun setOnSaveListener(listener: (MapItem) -> Unit) {
        onSaveListener = listener
    }

    fun setOnDeleteListener(listener: () -> Unit) {
        onDeleteListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_edit_map, null)

        // Récupérer les vues
        editMapName = view.findViewById(R.id.editMapName)
        spinnerCategory = view.findViewById(R.id.spinnerCategory)
        textDateAdded = view.findViewById(R.id.textDateAdded)
        tabLayout = view.findViewById(R.id.tabLayout)
        lightModeContent = view.findViewById(R.id.lightModeContent)
        darkModeContent = view.findViewById(R.id.darkModeContent)
        textLightImagePath = view.findViewById(R.id.textLightImagePath)
        textDarkImagePath = view.findViewById(R.id.textDarkImagePath)
        btnSelectLightImage = view.findViewById(R.id.btnSelectLightImage)
        btnSelectDarkImage = view.findViewById(R.id.btnSelectDarkImage)
        checkDefaultMap = view.findViewById(R.id.checkDefaultMap)
        btnDelete = view.findViewById(R.id.btnDelete)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSave = view.findViewById(R.id.btnSave)

        // Setup des onglets
        setupTabs()

        // Charger les catégories
        setupCategorySpinner()

        // Mode ajout ou édition
        if (mode == Mode.EDIT && existingMap != null) {
            setupEditMode()
        } else {
            setupAddMode()
        }

        // Listeners
        btnSelectLightImage.setOnClickListener { selectLightImageLauncher.launch(arrayOf("image/*")) }
        btnSelectDarkImage.setOnClickListener { selectDarkImageLauncher.launch(arrayOf("image/*")) }
        btnCancel.setOnClickListener { dismiss() }
        btnSave.setOnClickListener { saveMap() }
        btnDelete.setOnClickListener { deleteMap() }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (mode == Mode.ADD) "Ajouter une carte" else "Modifier la carte")
            .setView(view)
            .create()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Light"))
        tabLayout.addTab(tabLayout.newTab().setText("Dark"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        lightModeContent.visibility = View.VISIBLE
                        darkModeContent.visibility = View.GONE
                    }
                    1 -> {
                        lightModeContent.visibility = View.GONE
                        darkModeContent.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupCategorySpinner() {
        val storage = MapStorage(requireContext())
        val database = storage.load()

        val categories = database.categories.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = adapter
    }

    private fun setupAddMode() {
        // Masquer la date et le bouton supprimer
        view?.findViewById<TextView>(R.id.labelDateAdded)?.visibility = View.GONE
        textDateAdded.visibility = View.GONE
        btnDelete.visibility = View.GONE
    }

    private fun setupEditMode() {
        existingMap?.let { map ->
            editMapName.setText(map.name)

            // Date
            val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.FRENCH)
            textDateAdded.text = dateFormat.format(Date(map.dateAdded))

            // Catégorie
            val storage = MapStorage(requireContext())
            val database = storage.load()
            val categoryIndex = database.categories.indexOfFirst { it.id == map.categoryId }
            if (categoryIndex != -1) {
                spinnerCategory.setSelection(categoryIndex)
            }

            // Images
            selectedLightUri = map.lightImageUri
            selectedDarkUri = map.darkImageUri

            textLightImagePath.text = if (map.hasLightMode && map.lightImageUri != null) {
                map.lightImageUri?.lastPathSegment ?: "Image Light"
            } else {
                "Aucune image"
            }

            textDarkImagePath.text = if (map.hasDarkMode && map.darkImageUri != null) {
                map.darkImageUri?.lastPathSegment ?: "Image Dark"
            } else {
                "Aucune image"
            }

            // Carte par défaut
            checkDefaultMap.isChecked = map.isDefault

            // Si c'est la carte par défaut, on ne peut pas la supprimer
            if (map.isDefault) {
                btnDelete.isEnabled = false
                btnDelete.alpha = 0.5f
            }
        }
    }

    private fun saveMap() {
        val name = editMapName.text.toString().trim()

        // Validation
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Veuillez entrer un nom", Toast.LENGTH_SHORT).show()
            return
        }

        // Au moins une image doit être sélectionnée
        if (selectedLightUri == null && selectedDarkUri == null) {
            Toast.makeText(requireContext(), "Veuillez sélectionner au moins une image (Light ou Dark)", Toast.LENGTH_SHORT).show()
            return
        }

        // Récupérer la catégorie sélectionnée
        val storage = MapStorage(requireContext())
        val database = storage.load()
        val categoryName = spinnerCategory.selectedItem.toString()
        val category = database.categories.find { it.name == categoryName }
            ?: database.categories.first { it.isSystem }

        // Créer ou mettre à jour la carte
        val map = if (mode == Mode.EDIT && existingMap != null) {
            existingMap!!.copy(
                name = name,
                categoryId = category.id,
                lightImageUri = selectedLightUri,
                darkImageUri = selectedDarkUri,
                hasLightMode = selectedLightUri != null,
                hasDarkMode = selectedDarkUri != null,
                isDefault = checkDefaultMap.isChecked
            )
        } else {
            MapItem(
                id = UUID.randomUUID().toString(),
                name = name,
                categoryId = category.id,
                lightImageUri = selectedLightUri,
                darkImageUri = selectedDarkUri,
                dateAdded = System.currentTimeMillis(),
                isDefault = checkDefaultMap.isChecked,
                hasLightMode = selectedLightUri != null,
                hasDarkMode = selectedDarkUri != null,
                isBuiltIn = false
            )
        }

        onSaveListener?.invoke(map)
        dismiss()
    }

    private fun deleteMap() {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer la carte")
            .setMessage("Êtes-vous sûr de vouloir supprimer cette carte ? Cette action est irréversible.")
            .setPositiveButton("Supprimer") { _, _ ->
                onDeleteListener?.invoke()
                dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
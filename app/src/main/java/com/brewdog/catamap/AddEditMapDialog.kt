package com.brewdog.catamap

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AddEditMapDialog : DialogFragment() {

    enum class Mode { ADD, EDIT }

    private lateinit var mode: Mode
    private var existingMap: MapItem? = null
    private var onSaveListener: ((MapItem) -> Unit)? = null
    private var onDeleteListener: (() -> Unit)? = null
    private var selectedLightUri: Uri? = null
    private var selectedDarkUri: Uri? = null

    private lateinit var editMapName: TextInputEditText
    private lateinit var spinnerCategory: Spinner
    private lateinit var textDateAdded: TextView
    private lateinit var btnSelectImage: Button
    private lateinit var textImageStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var checkDefaultMap: CheckBox
    private lateinit var btnDelete: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            processImage(it)
        }
    }

    companion object {
        fun newInstance(mode: Mode, existingMap: MapItem? = null) = AddEditMapDialog().apply {
            this.mode = mode
            this.existingMap = existingMap
        }
    }

    fun setOnSaveListener(listener: (MapItem) -> Unit) {
        onSaveListener = listener
    }

    fun setOnDeleteListener(listener: () -> Unit) {
        onDeleteListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_add_edit_map, null)
        initializeViews(view)
        setupListeners()
        populateFields()

        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()

        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    private fun initializeViews(view: View) {
        editMapName = view.findViewById(R.id.editMapName)
        spinnerCategory = view.findViewById(R.id.spinnerCategory)
        textDateAdded = view.findViewById(R.id.textDateAdded)
        btnSelectImage = view.findViewById(R.id.btnSelectImage)
        textImageStatus = view.findViewById(R.id.textImageStatus)
        progressBar = view.findViewById(R.id.progressBar)
        checkDefaultMap = view.findViewById(R.id.checkDefaultMap)
        btnDelete = view.findViewById(R.id.btnDelete)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSave = view.findViewById(R.id.btnSave)

        if (mode == Mode.ADD) btnDelete.visibility = View.GONE
    }

    private fun setupListeners() {
        btnSelectImage.setOnClickListener { selectImageLauncher.launch(arrayOf("image/*")) }
        btnCancel.setOnClickListener { dismiss() }
        btnSave.setOnClickListener { saveMap() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun processImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                textImageStatus.text = "🔍 Analyse..."
                progressBar.visibility = View.VISIBLE
                btnSelectImage.isEnabled = false
                btnSave.isEnabled = false

                val isDark = withContext(Dispatchers.IO) {
                    MapModeDetector.isImageDarkModeSync(requireContext(), uri)
                }

                if (!isAdded) return@launch
                textImageStatus.text = "⏳ Génération..."

                val negativeUri = withContext(Dispatchers.IO) {
                    MapImageConverter.generateAlternateVersionOptimized(
                        requireContext(), uri, MapImageConverter.ConversionMode.INVERT
                    )
                }

                if (!isAdded) return@launch

                if (negativeUri != null) {
                    if (isDark) {
                        selectedDarkUri = uri
                        selectedLightUri = negativeUri
                    } else {
                        selectedLightUri = uri
                        selectedDarkUri = negativeUri
                    }
                    textImageStatus.text = "Les deux versions prêtes"
                    Toast.makeText(requireContext(), "✓ Carte prête !", Toast.LENGTH_SHORT).show()
                } else {
                    throw Exception("Erreur génération")
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                textImageStatus.text = "❌ ${e.message}"
                selectedLightUri = uri
            } finally {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    btnSelectImage.isEnabled = true
                    btnSave.isEnabled = true
                }
            }
        }
    }

    private fun populateFields() {
        val storage = MapStorage(requireContext())
        val database = storage.load()
        val categoryNames = database.categories.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categoryNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = adapter

        if (mode == Mode.EDIT && existingMap != null) {
            existingMap?.let { map ->
                editMapName.setText(map.name)
                textDateAdded.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(map.dateAdded))
                checkDefaultMap.isChecked = map.isDefault
                selectedLightUri = map.lightImageUri
                selectedDarkUri = map.darkImageUri
                textImageStatus.text = if (map.lightImageUri != null || map.darkImageUri != null) "✓ Image présente" else "Aucune image"
                val category = database.categories.find { it.id == map.categoryId }
                spinnerCategory.setSelection(categoryNames.indexOf(category?.name).coerceAtLeast(0))
            }
        } else {
            textDateAdded.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            textImageStatus.text = "Aucune image sélectionnée"
        }
    }

    private fun saveMap() {
        val name = editMapName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Entrez un nom", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLightUri == null && selectedDarkUri == null) {
            Toast.makeText(requireContext(), "Sélectionnez une image", Toast.LENGTH_SHORT).show()
            return
        }

        val storage = MapStorage(requireContext())
        val database = storage.load()
        val category = database.categories.find { it.name == spinnerCategory.selectedItem?.toString() }
        val map = MapItem(
            id = existingMap?.id ?: UUID.randomUUID().toString(),
            name = name,
            categoryId = category?.id ?: Category.UNCATEGORIZED_ID,
            lightImageUri = selectedLightUri,
            darkImageUri = selectedDarkUri,
            dateAdded = existingMap?.dateAdded ?: System.currentTimeMillis(),
            isDefault = checkDefaultMap.isChecked,
            hasLightMode = selectedLightUri != null,
            hasDarkMode = selectedDarkUri != null,
            isBuiltIn = false
        )
        onSaveListener?.invoke(map)
        dismiss()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer")
            .setMessage("Supprimer cette carte ?")
            .setPositiveButton("Oui") { _, _ -> onDeleteListener?.invoke(); dismiss() }
            .setNegativeButton("Non", null)
            .show()
    }
}
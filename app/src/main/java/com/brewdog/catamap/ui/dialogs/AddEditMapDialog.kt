package com.brewdog.catamap.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.brewdog.catamap.R
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.data.repository.MapRepository
import com.brewdog.catamap.utils.image.MapImageConverter
import com.brewdog.catamap.utils.image.MapModeDetector
import com.brewdog.catamap.utils.image.MinimapGenerator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import com.brewdog.catamap.data.models.Category

class AddEditMapDialog : DialogFragment() {

    enum class Mode { ADD, EDIT }

    private lateinit var mode: Mode
    private var existingMap: MapItem? = null
    private var onSaveListener: ((MapItem) -> Unit)? = null
    private var onDeleteListener: (() -> Unit)? = null
    private var selectedLightUri: Uri? = null
    private var selectedDarkUri: Uri? = null
    private var selectedLightMinimapUri: Uri? = null
    private var selectedDarkMinimapUri: Uri? = null
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

    // Stocker le Job pour pouvoir l'annuler
    private var imageProcessingJob: Job? = null

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
        // Annuler le job précédent s'il existe
        imageProcessingJob?.cancel()

        // Stocker le nouveau job
        imageProcessingJob = lifecycleScope.launch {
            try {
                // Vérifier dès le début si le fragment est attaché
                if (!isAdded) return@launch

                // Mise à jour UI - phase 1: Analyse
                withContext(Dispatchers.Main) {
                    textImageStatus.text = "Analyse..."
                    progressBar.visibility = View.VISIBLE
                    btnSelectImage.isEnabled = false
                    btnSave.isEnabled = false
                }

                // Analyse en background
                val isDark = withContext(Dispatchers.IO) {
                    MapModeDetector.isImageDarkModeSync(requireContext(), uri)
                }

                // Vérifier à nouveau après l'opération IO
                if (!isAdded) return@launch

                // Mise à jour UI - phase 2: Génération version alternative
                withContext(Dispatchers.Main) {
                    textImageStatus.text = "Generation version alternative..."
                }

                // Génération version alternative en background
                val negativeUri = withContext(Dispatchers.IO) {
                    MapImageConverter.generateAlternateVersionOptimized(
                        requireContext(), uri, MapImageConverter.ConversionMode.INVERT
                    )
                }

                // Vérifier une nouvelle fois
                if (!isAdded) return@launch

                // Mise à jour UI - phase 3: Génération minimap light
                withContext(Dispatchers.Main) {
                    textImageStatus.text = "Generation minimap light..."
                }

                val lightUri = if (isDark) negativeUri else uri
                val lightMinimapUri = if (lightUri != null) {
                    withContext(Dispatchers.IO) {
                        MinimapGenerator.generateMinimap(requireContext(), lightUri)
                    }
                } else null

                if (!isAdded) return@launch

                // Mise à jour UI - phase 4: Génération minimap dark
                withContext(Dispatchers.Main) {
                    textImageStatus.text = "Generation minimap dark..."
                }

                val darkUri = if (isDark) uri else negativeUri
                val darkMinimapUri = if (darkUri != null) {
                    withContext(Dispatchers.IO) {
                        MinimapGenerator.generateMinimap(requireContext(), darkUri)
                    }
                } else null

                // Vérifier une dernière fois avant de finaliser
                if (!isAdded) return@launch

                // Mise à jour UI finale
                withContext(Dispatchers.Main) {
                    if (negativeUri != null && lightMinimapUri != null && darkMinimapUri != null) {
                        if (isDark) {
                            selectedDarkUri = uri
                            selectedLightUri = negativeUri
                        } else {
                            selectedLightUri = uri
                            selectedDarkUri = negativeUri
                        }

                        // Stocker les minimap
                        selectedLightMinimapUri = lightMinimapUri
                        selectedDarkMinimapUri = darkMinimapUri

                        textImageStatus.text = "Carte et minimap pretes !"
                        Toast.makeText(requireContext(), "Carte prete !", Toast.LENGTH_SHORT).show()
                    } else {
                        throw Exception("Erreur generation")
                    }
                }

            } catch (e: CancellationException) {
                // Gestion normale de l'annulation
                Log.d("AddEditMapDialog", "Traitement image annulé")

            } catch (e: Exception) {
                // Gestion des autres erreurs
                if (isAdded) {
                    withContext(Dispatchers.Main) {
                        textImageStatus.text = "Erreur: ${e.message}"
                        selectedLightUri = uri
                    }
                }

            } finally {
                // Réactiver les boutons dans tous les cas
                if (isAdded) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        btnSelectImage.isEnabled = true
                        btnSave.isEnabled = true
                    }
                }
            }
        }
    }


    private fun populateFields() {
        val repository = MapRepository(requireContext())
        val database = repository.loadDatabase()
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
                selectedLightMinimapUri = map.lightMinimapUri
                selectedDarkMinimapUri = map.darkMinimapUri
                textImageStatus.text = if (map.lightImageUri != null || map.darkImageUri != null) "Image presente" else "Aucune image"
                val category = database.categories.find { it.id == map.categoryId }
                spinnerCategory.setSelection(categoryNames.indexOf(category?.name).coerceAtLeast(0))
            }
        } else {
            textDateAdded.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            textImageStatus.text = "Aucune image selectionnee"
        }
    }

    private fun saveMap() {
        val name = editMapName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Entrez un nom", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLightUri == null && selectedDarkUri == null) {
            Toast.makeText(requireContext(), "Selectionnez une image", Toast.LENGTH_SHORT).show()
            return
        }

        val repository = MapRepository(requireContext())
        val database = repository.loadDatabase()
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
            isBuiltIn = false,
            lightMinimapUri = selectedLightMinimapUri,
            darkMinimapUri = selectedDarkMinimapUri
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

    // Annuler les coroutines lors de la fermeture du dialog
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Annuler le traitement d'image en cours si le dialog est fermé
        imageProcessingJob?.cancel()
    }

    // Nettoyer en cas de destruction du fragment
    override fun onDestroyView() {
        super.onDestroyView()
        imageProcessingJob?.cancel()
        imageProcessingJob = null
    }
}
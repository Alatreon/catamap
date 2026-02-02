package com.brewdog.catamap.ui.annotation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brewdog.catamap.R
import com.brewdog.catamap.data.repository.AnnotationRepository
import com.brewdog.catamap.domain.annotation.LayerChangeListener
import com.brewdog.catamap.domain.annotation.LayerManager
import com.brewdog.catamap.domain.annotation.models.Layer
import com.brewdog.catamap.ui.activities.MainActivity
import com.brewdog.catamap.utils.logging.Logger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Bottom Sheet pour l'édition des calques
 *
 * Features :
 * - Liste des calques
 * - Ajout/suppression/renommage
 * - Drag & drop pour réorganiser
 * - Toggle visibilité
 * - Activation calque
 */
class EditBottomSheet : BottomSheetDialogFragment(), LayerChangeListener {

    companion object {
        private const val TAG = "EditBottomSheet"
        private const val ARG_MAP_ID = "map_id"

        fun newInstance(mapId: String, layerManager: LayerManager): EditBottomSheet {
            return EditBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MAP_ID, mapId)
                }
                this.layerManager = layerManager  // ← Utiliser le LayerManager partagé
            }
        }
    }

    // UI Components
    private lateinit var btnTools: Button
    private lateinit var btnToggleAllLayers: Button
    private lateinit var btnAddLayer: View
    private lateinit var layersRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView

    // Managers
    private lateinit var layerManager: LayerManager
    private lateinit var layerAdapter: LayerAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    // State
    private var mapId: String? = null
    private var allLayersHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mapId = arguments?.getString(ARG_MAP_ID)

        Logger.i(TAG, "EditBottomSheet created for map: $mapId")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Logger.entry(TAG, "onViewCreated")

        // Configurer le BottomSheetBehavior
        setupBottomSheetBehavior()

        initViews(view)
        initLayerManager()
        initRecyclerView()
        setupListeners()

        // Charger les annotations
        loadAnnotations()
    }

    /**
     * Configure le comportement du Bottom Sheet
     */
    private fun setupBottomSheetBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as? com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheet = bottomSheetDialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )

            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = 400.dpToPx()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.isHideable = true
                behavior.skipCollapsed = false

                Logger.d(TAG, "BottomSheetBehavior configured: peekHeight=400dp")
            }
        }
    }

    /**
     * Convertit dp en pixels
     */
    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    private fun initViews(view: View) {
        btnTools = view.findViewById(R.id.btnTools)
        btnToggleAllLayers = view.findViewById(R.id.btnToggleAllLayers)
        btnAddLayer = view.findViewById(R.id.btnAddLayer)
        layersRecyclerView = view.findViewById(R.id.layersRecyclerView)
        emptyStateText = view.findViewById(R.id.emptyStateText)
    }

    private fun initLayerManager() {
        // ✅ Le LayerManager est déjà initialisé (partagé avec MainActivity)
        if (!::layerManager.isInitialized) {
            Logger.e(TAG, "LayerManager not provided!")
            return
        }

        layerManager.addListener(this)
        Logger.d(TAG, "LayerManager shared from MainActivity")
    }

    private fun initRecyclerView() {
        // Adapter
        layerAdapter = LayerAdapter(
            onLayerClick = { layer -> onLayerClicked(layer) },
            onToggleVisibility = { layer -> onToggleVisibility(layer) },
            onDelete = { layer -> onDeleteLayer(layer) },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) },
            onDoubleClick = { layer -> onRenameLayer(layer) }
        )

        // Layout Manager
        layersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        layersRecyclerView.adapter = layerAdapter

        // ItemTouchHelper pour drag & drop
        val callback = LayerItemTouchHelper(
            onMove = { fromPosition, toPosition ->
                onLayerMoved(fromPosition, toPosition)
            }
        )
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(layersRecyclerView)

        Logger.d(TAG, "RecyclerView initialized")
    }

    private fun setupListeners() {
        // Bouton Outils
        // On ne désactive pas le bouton, on change juste son apparence
        // et on affiche le toast si nécessaire
        btnTools.setOnClickListener {
            val layers = layerManager.getLayers()
            val activeLayer = layerManager.getActiveLayer()
            val canUseTools = layers.isNotEmpty() && activeLayer != null && activeLayer.isVisible

            if (canUseTools) {
                showFeedback("Outils à venir dans la prochaine version")
            } else {
                // Toast quand les conditions ne sont pas remplies
                android.widget.Toast.makeText(
                    requireContext(),
                    "Il faut au moins un calque visible et actif pour commencer les modifications",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Toggle tous les calques
        btnToggleAllLayers.setOnClickListener {
            toggleAllLayers()
        }

        // Ajouter un calque
        btnAddLayer.setOnClickListener {
            showAddLayerDialog()
        }

        // Bouton Outils
        btnTools.setOnClickListener {
            val layers = layerManager.getLayers()
            val activeLayer = layerManager.getActiveLayer()
            val canUseTools = layers.isNotEmpty() && activeLayer != null && activeLayer.isVisible

            if (canUseTools) {
                // Fermer le Bottom Sheet
                dismiss()

                // Afficher l'overlay des outils
                (activity as? MainActivity)?.showToolsOverlay()

                Logger.i(TAG, "Opening tools overlay")
            } else {
                // Toast si conditions non remplies
                android.widget.Toast.makeText(
                    requireContext(),
                    "Il faut au moins un calque visible et actif pour commencer les modifications",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadAnnotations() {
        val mapId = this.mapId ?: return

        Logger.entry(TAG, "loadAnnotations", mapId)

        lifecycleScope.launch {
            try {
                layerManager.loadAnnotations(mapId)
                Logger.i(TAG, "Annotations loaded successfully")

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load annotations", e)
                showFeedback("Erreur lors du chargement")
            }
        }
    }

    // ========== LAYER OPERATIONS ==========

    private fun onLayerClicked(layer: Layer) {
        Logger.entry(TAG, "onLayerClicked", layer.name)

        // On peut activer un calque même s'il est masqué
        val result = layerManager.setActiveLayer(layer.id)

        result.onSuccess {
            showFeedback("Calque activé : ${layer.name}")
            updateToolsButtonState()
        }.onFailure { error ->
            Logger.w(TAG, "Failed to activate layer", error)
            showFeedback(error.message ?: "Erreur")
        }
    }

    private fun onToggleVisibility(layer: Layer) {
        Logger.entry(TAG, "onToggleVisibility", layer.name)

        val result = layerManager.toggleLayerVisibility(layer.id)

        result.onSuccess {
            // Récupérer le nouvel état depuis le layer
            val updatedLayer = layerManager.getLayers().find { it.id == layer.id }
            val newVisibility = updatedLayer?.isVisible ?: false

            val message = if (newVisibility) {
                "Calque \"${layer.name}\" affiché"
            } else {
                "Calque \"${layer.name}\" masqué"
            }
            showFeedback(message)

            Logger.i(TAG, "Layer ${layer.name} visibility: $newVisibility")

            // Mettre à jour l'état du bouton outils
            updateToolsButtonState()
        }.onFailure { error ->
            Logger.e(TAG, "Failed to toggle visibility", error)
            showFeedback(error.message ?: "Erreur")
        }
    }



    private fun onDeleteLayer(layer: Layer) {
        Logger.entry(TAG, "onDeleteLayer", layer.name)

        // Si le calque est vide, suppression immédiate
        if (layer.isEmpty()) {
            deleteLayerConfirmed(layer)
            return
        }

        // Sinon, demander confirmation
        showDeleteConfirmationDialog(layer)
    }

    private fun deleteLayerConfirmed(layer: Layer) {
        val result = layerManager.removeLayer(layer.id)

        result.onSuccess {
            showFeedback("Calque supprimé")
        }.onFailure { error ->
            Logger.w(TAG, "Failed to delete layer", error)
            showFeedback(error.message ?: "Erreur")
        }
    }

    private fun onRenameLayer(layer: Layer) {
        Logger.entry(TAG, "onRenameLayer", layer.name)

        showRenameLayerDialog(layer)
    }

    private fun onLayerMoved(fromPosition: Int, toPosition: Int) {
        Logger.v(TAG, "Layer moved: $fromPosition → $toPosition")

        // Mettre à jour l'ordre dans l'adapter
        val layers = layerAdapter.getLayers().toMutableList()
        val movedLayer = layers.removeAt(fromPosition)
        layers.add(toPosition, movedLayer)

        // Mettre à jour l'adapter
        layerAdapter.updateLayers(layers, layerManager.getActiveLayerId())

        // Sauvegarder le nouvel ordre
        val result = layerManager.reorderLayers(layers)

        result.onFailure { error ->
            Logger.e(TAG, "Failed to reorder layers", error)
            // Recharger l'ordre original
            onLayersChanged(layerManager.getLayers(), layerManager.getActiveLayerId() ?: "")
        }
    }

    private fun toggleAllLayers() {
        Logger.entry(TAG, "toggleAllLayers")

        if (allLayersHidden) {
            // Afficher tous
            val result = layerManager.showAllLayers()

            result.onSuccess {
                allLayersHidden = false
                btnToggleAllLayers.text = "Masquer tous"
                showFeedback("Tous les calques affichés")
            }
        } else {
            // Masquer tous (vraiment tous, y compris le calque actif)
            val result = layerManager.hideAllLayers()

            result.onSuccess {
                allLayersHidden = true
                btnToggleAllLayers.text = "Afficher tous"
                showFeedback("Tous les calques masqués")
            }
        }

        updateToolsButtonState()
    }

    // ========== DIALOGS ==========

    private fun showAddLayerDialog() {
        Logger.entry(TAG, "showAddLayerDialog")

        // Récupérer les noms existants
        val existingNames = layerManager.getLayers().map { it.name }

        val dialog = AddLayerDialog.newInstance(existingNames) { name ->
            onAddLayer(name)
        }
        dialog.show(childFragmentManager, "AddLayerDialog")
    }

    private fun onAddLayer(name: String) {
        Logger.entry(TAG, "onAddLayer", name)

        val result = layerManager.addLayer(name)

        result.onSuccess { layer ->
            showFeedback("Calque créé : ${layer.name}")
        }.onFailure { error ->
            Logger.w(TAG, "Failed to add layer", error)
            // Afficher l'erreur à l'utilisateur
            showFeedback(error.message ?: "Erreur lors de la création du calque")
        }
    }

    private fun showRenameLayerDialog(layer: Layer) {
        // Récupérer les noms existants
        val existingNames = layerManager.getLayers().map { it.name }

        val dialog = RenameLayerDialog.newInstance(layer.id, layer.name, existingNames) { newName ->
            onRenameLayerConfirmed(layer.id, newName)
        }
        dialog.show(childFragmentManager, "RenameLayerDialog")
    }

    private fun onRenameLayerConfirmed(layerId: String, newName: String) {
        Logger.entry(TAG, "onRenameLayerConfirmed", newName)

        val result = layerManager.renameLayer(layerId, newName)

        result.onSuccess {
            showFeedback("Calque renommé")
        }.onFailure { error ->
            Logger.w(TAG, "Failed to rename layer", error)
            showFeedback(error.message ?: "Erreur")
        }
    }

    private fun showDeleteConfirmationDialog(layer: Layer) {
        val count = layer.annotations.size

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Supprimer le calque ?")
            .setMessage("Le calque \"${layer.name}\" contient $count annotation(s).\n\nCette action est irréversible.")
            .setPositiveButton("Supprimer") { _, _ ->
                deleteLayerConfirmed(layer)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ========== UI UPDATES ==========

    override fun onLayersChanged(layers: List<Layer>, activeLayerId: String) {
        Logger.v(TAG, "onLayersChanged: ${layers.size} layers")

        // Afficher/masquer le message liste vide
        if (layers.isEmpty()) {
            layersRecyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
        } else {
            layersRecyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
        }

        // Mettre à jour l'adapter
        layerAdapter.updateLayers(layers, activeLayerId)

        // Mettre à jour l'état du bouton toggle
        updateToggleButtonState(layers)

        // Mettre à jour l'état du bouton outils
        updateToolsButtonState()
    }

    private fun updateToggleButtonState(layers: List<Layer>) {
        val visibleCount = layers.count { it.isVisible }

        // Si AUCUN calque visible → on peut "Afficher tous"
        // Si AU MOINS UN visible → on peut "Masquer tous"
        allLayersHidden = visibleCount == 0

        btnToggleAllLayers.text = if (allLayersHidden) {
            "Afficher tous"
        } else {
            "Masquer tous"
        }

        Logger.d(TAG, "Toggle button state: allHidden=$allLayersHidden, visible=$visibleCount/${layers.size}")
    }

    private fun updateToolsButtonState() {
        val layers = layerManager.getLayers()
        val activeLayer = layerManager.getActiveLayer()

        // Bouton grisé si :
        // 1. Aucun calque (liste vide)
        // 2. OU calque actif masqué
        val shouldEnable = layers.isNotEmpty() && activeLayer != null && activeLayer.isVisible

        // Ne pas désactiver le bouton pour permettre le clic et le Toast
        // On change juste l'apparence
        btnTools.alpha = if (shouldEnable) 1.0f else 0.5f

        Logger.d(TAG, "Tools button: shouldEnable=$shouldEnable (layers=${layers.size}, active=${activeLayer?.name}, visible=${activeLayer?.isVisible})")
    }

    // ========== FEEDBACK ==========

    private fun showFeedback(message: String) {
        view?.let { v ->
            Snackbar.make(v, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ========== LIFECYCLE ==========

    override fun onDestroyView() {
        super.onDestroyView()

        Logger.entry(TAG, "onDestroyView")

        // Sauvegarder immédiatement avant de fermer
        lifecycleScope.launch {
            try {
                layerManager.saveAnnotationsImmediate()
                Logger.i(TAG, "Annotations saved on close")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save on close", e)
            }
        }

        // Cleanup listeners seulement
        layerManager.removeListener(this)

        Logger.d(TAG, "LayerManager kept alive for ToolsOverlay")
    }

    /**
     * Expose le LayerManager pour utilisation par MainActivity/ToolsOverlay
     */
    fun getLayerManager(): LayerManager {
        return layerManager
    }
}
package com.brewdog.catamap.domain.annotation

import com.brewdog.catamap.data.repository.AnnotationRepository
import com.brewdog.catamap.domain.annotation.models.Layer
import com.brewdog.catamap.domain.annotation.models.MapAnnotations
import com.brewdog.catamap.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gestionnaire de la logique métier des calques
 * Responsabilités :
 * - CRUD des calques
 * - Validation des règles métier
 * - Sauvegarde automatique
 * - Gestion du calque actif
 */
class LayerManager(
    private val repository: AnnotationRepository,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "LayerManager"
        private const val MAX_LAYER_NAME_LENGTH = 30
    }

    // État actuel
    private var currentAnnotations: MapAnnotations? = null
    private var listeners = mutableListOf<LayerChangeListener>()

    /**
     * Charge les annotations d'une carte
     * Crée une structure par défaut si aucune n'existe
     */
    suspend fun loadAnnotations(mapId: String): MapAnnotations {
        Logger.entry(TAG, "loadAnnotations", mapId)

        return withContext(Dispatchers.IO) {
            val annotations = repository.loadAnnotations(mapId)
                ?: MapAnnotations.createDefault(mapId)

            currentAnnotations = annotations

            Logger.i(TAG, "Annotations loaded: ${annotations.layers.size} layers")
            annotations.logState()

            notifyListeners()
            annotations
        }
    }

    /**
     * Sauvegarde les annotations (temps réel avec debounce)
     */
    fun saveAnnotations() {
        val annotations = currentAnnotations ?: return

        Logger.v(TAG, "Triggering save (debounced)")
        repository.saveAnnotations(annotations.mapId, annotations)
    }

    /**
     * Sauvegarde immédiate (sans debounce)
     */
    suspend fun saveAnnotationsImmediate() {
        val annotations = currentAnnotations ?: return

        Logger.entry(TAG, "saveAnnotationsImmediate")
        repository.saveAnnotationsImmediate(annotations.mapId, annotations)
        Logger.i(TAG, "Annotations saved immediately")
    }

    /**
     * Récupère les annotations actuelles
     */
    fun getCurrentAnnotations(): MapAnnotations? = currentAnnotations

    /**
     * Récupère la liste des calques
     */
    fun getLayers(): List<Layer> {
        return currentAnnotations?.layers?.toList() ?: emptyList()
    }

    /**
     * Récupère le calque actif
     */
    fun getActiveLayer(): Layer? {
        return currentAnnotations?.getActiveLayer()
    }

    /**
     * Récupère l'ID du calque actif
     */
    fun getActiveLayerId(): String? {
        return currentAnnotations?.activeLayerId
    }

    // ========== CRUD OPERATIONS ==========

    /**
     * Ajoute un nouveau calque
     */
    fun addLayer(name: String): Result<Layer> {
        Logger.entry(TAG, "addLayer", name)

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        // Validation du nom
        val validationResult = validateLayerName(name)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }

        try {
            // Créer le nouveau calque
            val newLayer = Layer.createDefault(
                name = name.trim(),
                zIndex = annotations.layers.size
            )

            // Ajouter à la structure
            annotations.addLayer(newLayer)

            // Activer le nouveau calque
            annotations.setActiveLayer(newLayer.id)

            Logger.i(TAG, "Layer added: $name (total: ${annotations.layers.size})")

            // Sauvegarder
            saveAnnotations()
            notifyListeners()

            return Result.success(newLayer)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add layer", e)
            return Result.failure(e)
        }
    }

    /**
     * Supprime un calque
     */
    fun removeLayer(layerId: String): Result<Unit> {
        Logger.entry(TAG, "removeLayer", layerId)

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        val layer = annotations.findLayer(layerId)
            ?: return Result.failure(IllegalArgumentException("Layer not found"))

        try {
            // Supprimer (plus de restriction sur le dernier calque)
            val removed = annotations.removeLayer(layerId)

            if (removed) {
                Logger.i(TAG, "Layer removed: ${layer.name}")
                saveAnnotations()
                notifyListeners()
                return Result.success(Unit)
            } else {
                return Result.failure(IllegalStateException("Failed to remove layer"))
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to remove layer", e)
            return Result.failure(e)
        }
    }

    /**
     * Renomme un calque
     */
    fun renameLayer(layerId: String, newName: String): Result<Unit> {
        Logger.entry(TAG, "renameLayer", layerId, newName)

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        val layer = annotations.findLayer(layerId)
            ?: return Result.failure(IllegalArgumentException("Layer not found"))

        // Validation du nouveau nom
        val validationResult = validateLayerName(newName, excludeLayerId = layerId)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }

        try {
            val oldName = layer.name
            layer.name = newName.trim()

            Logger.i(TAG, "Layer renamed: $oldName → $newName")

            saveAnnotations()
            notifyListeners()

            return Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to rename layer", e)
            return Result.failure(e)
        }
    }

    /**
     * Active un calque
     */
    fun setActiveLayer(layerId: String): Result<Unit> {
        Logger.entry(TAG, "setActiveLayer", layerId)

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        val layer = annotations.findLayer(layerId)
            ?: return Result.failure(IllegalArgumentException("Layer not found"))

        // Plus de restriction sur la visibilité - un calque peut être actif et masqué
        val success = annotations.setActiveLayer(layerId)

        if (success) {
            Logger.i(TAG, "Active layer changed to: ${layer.name} (visible=${layer.isVisible})")
            saveAnnotations()
            notifyListeners()
            return Result.success(Unit)
        } else {
            return Result.failure(IllegalStateException("Failed to set active layer"))
        }
    }

    /**
     * Toggle la visibilité d'un calque
     */
    fun toggleLayerVisibility(layerId: String): Result<Unit> {
        Logger.entry(TAG, "toggleLayerVisibility", layerId)

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        val layer = annotations.findLayer(layerId)
            ?: return Result.failure(IllegalArgumentException("Layer not found"))

        try {
            // Toggle la visibilité (plus de restriction)
            layer.isVisible = !layer.isVisible

            Logger.i(TAG, "Layer visibility toggled: ${layer.name} → ${layer.isVisible}")

            saveAnnotations()
            notifyListeners()

            return Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to toggle visibility", e)
            return Result.failure(e)
        }
    }

    /**
     * Masque tous les calques (vraiment tous)
     */
    fun hideAllLayers(): Result<Unit> {
        Logger.entry(TAG, "hideAllLayers")

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        try {
            annotations.layers.forEach { it.isVisible = false }

            Logger.i(TAG, "All layers hidden (including active)")

            saveAnnotations()
            notifyListeners()

            return Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to hide all layers", e)
            return Result.failure(e)
        }
    }

    /**
     * Affiche tous les calques
     */
    fun showAllLayers(): Result<Unit> {
        Logger.entry(TAG, "showAllLayers")

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        try {
            annotations.showAllLayers()

            Logger.i(TAG, "All layers shown")

            saveAnnotations()
            notifyListeners()

            return Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show all layers", e)
            return Result.failure(e)
        }
    }

    /**
     * Réorganise les calques (après drag & drop)
     */
    fun reorderLayers(newOrder: List<Layer>): Result<Unit> {
        Logger.entry(TAG, "reorderLayers")

        val annotations = currentAnnotations
            ?: return Result.failure(IllegalStateException("No annotations loaded"))

        try {
            annotations.reorderLayers(newOrder)

            Logger.i(TAG, "Layers reordered")

            saveAnnotations()
            notifyListeners()

            return Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to reorder layers", e)
            return Result.failure(e)
        }
    }

    // ========== VALIDATION ==========

    /**
     * Valide un nom de calque
     */
    private fun validateLayerName(name: String, excludeLayerId: String? = null): Result<Unit> {
        val trimmedName = name.trim()

        // Nom vide
        if (trimmedName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Le nom ne peut pas être vide"))
        }

        // Longueur max
        if (trimmedName.length > MAX_LAYER_NAME_LENGTH) {
            return Result.failure(IllegalArgumentException("Le nom est trop long (max $MAX_LAYER_NAME_LENGTH caractères)"))
        }

        // Unicité
        val annotations = currentAnnotations ?: return Result.success(Unit)

        val isDuplicate = annotations.layers.any { layer ->
            layer.name.equals(trimmedName, ignoreCase = true) && layer.id != excludeLayerId
        }

        if (isDuplicate) {
            return Result.failure(IllegalArgumentException("Un calque avec ce nom existe déjà"))
        }

        return Result.success(Unit)
    }

    /**
     * Vérifie si le nom de calque est disponible
     */
    fun isLayerNameAvailable(name: String, excludeLayerId: String? = null): Boolean {
        return validateLayerName(name, excludeLayerId).isSuccess
    }

    // ========== LISTENERS ==========

    /**
     * Ajoute un listener pour les changements
     */
    fun addListener(listener: LayerChangeListener) {
        listeners.add(listener)
        Logger.d(TAG, "Listener added: ${listeners.size} total")
    }

    /**
     * Retire un listener
     */
    fun removeListener(listener: LayerChangeListener) {
        listeners.remove(listener)
        Logger.d(TAG, "Listener removed: ${listeners.size} remaining")
    }

    /**
     * Notifie tous les listeners
     */
    private fun notifyListeners() {
        val annotations = currentAnnotations ?: return

        scope.launch(Dispatchers.Main) {
            listeners.forEach { listener ->
                try {
                    listener.onLayersChanged(annotations.layers, annotations.activeLayerId)
                } catch (e: Exception) {
                    Logger.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        Logger.entry(TAG, "cleanup")
        listeners.clear()
        currentAnnotations = null
        Logger.i(TAG, "LayerManager cleaned up")
    }
}

/**
 * Interface pour écouter les changements de calques
 */
interface LayerChangeListener {
    fun onLayersChanged(layers: List<Layer>, activeLayerId: String)
}
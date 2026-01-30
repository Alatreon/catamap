package com.brewdog.catamap.domain.annotation.models

import com.brewdog.catamap.utils.logging.Logger

/**
 * Conteneur principal pour toutes les annotations d'une carte
 * Structure sauvegardée en JSON
 *
 * @param version Version du format de sauvegarde (pour migrations futures)
 * @param mapId ID de la carte associée
 * @param lastModified Timestamp de dernière modification
 * @param activeLayerId ID du calque actuellement actif
 * @param layers Liste des calques
 */
data class MapAnnotations(
    val version: Int = CURRENT_VERSION,
    val mapId: String,
    val lastModified: Long = System.currentTimeMillis(),
    var activeLayerId: String,
    val layers: MutableList<Layer>
) {
    companion object {
        private const val TAG = "MapAnnotations"
        const val CURRENT_VERSION = 1

        /**
         * Crée une structure vide avec un calque par défaut
         */
        fun createDefault(mapId: String): MapAnnotations {
            Logger.d(TAG, "Creating default annotations for map: $mapId")

            val defaultLayer = Layer.createDefault("Calque 1", zIndex = 0)

            return MapAnnotations(
                mapId = mapId,
                activeLayerId = defaultLayer.id,
                layers = mutableListOf(defaultLayer)
            ).also {
                Logger.i(TAG, "Default annotations created with 1 layer")
            }
        }
    }

    /**
     * Récupère le calque actif
     */
    fun getActiveLayer(): Layer? {
        return layers.find { it.id == activeLayerId }
    }

    /**
     * Définit un nouveau calque actif
     * Le calque peut être visible ou masqué
     */
    fun setActiveLayer(layerId: String): Boolean {
        Logger.entry(TAG, "setActiveLayer", layerId)

        val layer = layers.find { it.id == layerId }

        if (layer == null) {
            Logger.w(TAG, "Layer not found: $layerId")
            return false
        }

        activeLayerId = layerId
        Logger.i(TAG, "Active layer changed to: ${layer.name} (visible=${layer.isVisible})")
        return true
    }

    /**
     * Ajoute un nouveau calque
     */
    fun addLayer(layer: Layer) {
        Logger.entry(TAG, "addLayer", layer.name)

        // Vérifier unicité du nom
        if (layers.any { it.name == layer.name }) {
            Logger.w(TAG, "Layer name already exists: ${layer.name}")
            throw IllegalArgumentException("Un calque avec ce nom existe déjà")
        }

        layers.add(layer)
        Logger.i(TAG, "Layer added: ${layer.name}, total=${layers.size}")
    }

    /**
     * Supprime un calque
     * Peut supprimer le dernier calque
     */
    fun removeLayer(layerId: String): Boolean {
        Logger.entry(TAG, "removeLayer", layerId)

        val layer = layers.find { it.id == layerId }
        if (layer == null) {
            Logger.w(TAG, "Layer not found: $layerId")
            return false
        }

        val removed = layers.removeIf { it.id == layerId }

        if (removed) {
            Logger.i(TAG, "Layer removed: ${layer.name}, remaining=${layers.size}")

            // Si c'était le calque actif et qu'il reste des calques, activer le premier
            if (activeLayerId == layerId && layers.isNotEmpty()) {
                activeLayerId = layers.first().id
                Logger.i(TAG, "Active layer changed to: ${layers.first().name}")
            }
        }

        return removed
    }

    /**
     * Trouve un calque par son ID
     */
    fun findLayer(layerId: String): Layer? {
        return layers.find { it.id == layerId }
    }

    /**
     * Trouve un calque par son nom
     */
    fun findLayerByName(name: String): Layer? {
        return layers.find { it.name == name }
    }

    /**
     * Génère un nom unique pour un nouveau calque
     */
    fun generateUniqueLayerName(): String {
        var counter = layers.size + 1
        var name: String

        do {
            name = "Calque $counter"
            counter++
        } while (layers.any { it.name == name })

        Logger.d(TAG, "Generated unique name: $name")
        return name
    }

    /**
     * Vérifie si un nom de calque est disponible
     */
    fun isLayerNameAvailable(name: String, excludeLayerId: String? = null): Boolean {
        return !layers.any { it.name == name && it.id != excludeLayerId }
    }

    /**
     * Réorganise les calques (après drag & drop)
     */
    fun reorderLayers(newOrder: List<Layer>) {
        Logger.entry(TAG, "reorderLayers")

        if (newOrder.size != layers.size) {
            Logger.e(TAG, "Invalid reorder: size mismatch")
            return
        }

        layers.clear()
        layers.addAll(newOrder)

        // Mettre à jour les zIndex
        layers.forEachIndexed { index, layer ->
            layer.zIndex = index
        }

        Logger.i(TAG, "Layers reordered, new order: ${layers.map { it.name }}")
    }

    /**
     * Masque tous les calques sauf un (optionnel)
     */
    fun hideAllLayers(exceptLayerId: String? = null) {
        Logger.entry(TAG, "hideAllLayers", exceptLayerId)

        var hiddenCount = 0
        layers.forEach { layer ->
            if (layer.id != exceptLayerId && layer.isVisible) {
                layer.isVisible = false
                hiddenCount++
            }
        }

        Logger.i(TAG, "$hiddenCount layers hidden")

        // Assurer qu'au moins un calque reste visible
        if (layers.none { it.isVisible }) {
            val layerToShow = layers.firstOrNull { it.id == exceptLayerId }
                ?: layers.first()
            layerToShow.isVisible = true
            Logger.i(TAG, "Forced layer visible: ${layerToShow.name}")
        }
    }

    /**
     * Affiche tous les calques
     */
    fun showAllLayers() {
        Logger.entry(TAG, "showAllLayers")

        var shownCount = 0
        layers.forEach { layer ->
            if (!layer.isVisible) {
                layer.isVisible = true
                shownCount++
            }
        }

        Logger.i(TAG, "$shownCount layers shown")
    }

    /**
     * Compte le nombre total d'annotations
     */
    fun getTotalAnnotationCount(): Int {
        return layers.sumOf { it.annotations.size }
    }

    /**
     * Compte les calques visibles
     */
    fun getVisibleLayerCount(): Int {
        return layers.count { it.isVisible }
    }

    /**
     * Met à jour le timestamp de modification
     */
    fun touch() {
        // Note: lastModified est val, donc on ne peut pas le modifier directement
        // On le mettra à jour lors de la sérialisation JSON
        Logger.v(TAG, "Annotations touched (modified)")
    }

    /**
     * Valide la cohérence de la structure
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        // Au moins un calque
        if (layers.isEmpty()) {
            errors.add("Aucun calque")
        }

        // Calque actif existe et est visible
        val activeLayer = getActiveLayer()
        if (activeLayer == null) {
            errors.add("Calque actif introuvable")
        } else if (!activeLayer.isVisible) {
            errors.add("Calque actif masqué")
        }

        // Noms de calques uniques
        val names = layers.map { it.name }
        if (names.size != names.distinct().size) {
            errors.add("Noms de calques en double")
        }

        // Au moins un calque visible
        if (layers.none { it.isVisible }) {
            errors.add("Aucun calque visible")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Log l'état complet
     */
    fun logState() {
        Logger.state(TAG, "MapAnnotations", mapOf(
            "version" to version,
            "mapId" to mapId,
            "layers" to layers.size,
            "visibleLayers" to getVisibleLayerCount(),
            "totalAnnotations" to getTotalAnnotationCount(),
            "activeLayer" to (getActiveLayer()?.name ?: "none"),
            "lastModified" to lastModified
        ))

        layers.forEach { it.logState() }
    }
}

/**
 * Résultat de validation
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}
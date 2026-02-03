package com.brewdog.catamap.data.models

import android.net.Uri
import com.brewdog.catamap.utils.logging.Logger

/**
 * Représente une carte géologique
 */
data class MapItem(
    val id: String,                    // Identifiant unique
    var name: String,                  // Nom de la carte
    var categoryId: String,            // ID de la catégorie
    var lightImageUri: Uri?,           // URI de l'image light (null si image drawable)
    var darkImageUri: Uri?,            // URI de l'image dark (null si image drawable)
    val dateAdded: Long,               // Timestamp d'ajout
    var isDefault: Boolean = false,    // Carte par défaut
    var hasLightMode: Boolean = false, // Disponibilité mode light
    var hasDarkMode: Boolean = false,  // Disponibilité mode dark
    val isBuiltIn: Boolean = false,    // true si c'est une carte embarquée (non supprimable tant que par défaut)
    var lightMinimapUri: Uri?,         // URI de la minimap light
    var darkMinimapUri: Uri?          // URI de la minimap dark
) {
    companion object {
        private const val TAG = "MapItem"
        
        /**
         * Crée une carte d'exemple (built-in)
         */
        fun createExample(name: String = "EX_MAP"): MapItem {
            Logger.d(TAG, "Creating example map: $name")
            return MapItem(
                id = "example_map_2025",
                name = name,
                categoryId = Category.UNCATEGORIZED_ID,
                lightImageUri = null,
                darkImageUri = null,
                dateAdded = System.currentTimeMillis(),
                isDefault = true,
                hasLightMode = true,
                hasDarkMode = true,
                isBuiltIn = true,
                lightMinimapUri = null,
                darkMinimapUri = null
            )
        }
    }
    
    /**
     * Vérifie si la carte a au moins un mode disponible
     */
    fun hasAnyMode(): Boolean = hasLightMode || hasDarkMode
    
    /**
     * Vérifie si la carte supporte un mode spécifique
     */
    fun supportsMode(isDarkMode: Boolean): Boolean = if (isDarkMode) hasDarkMode else hasLightMode
    
    /**
     * Récupère l'URI de l'image pour un mode donné
     */
    fun getImageUri(isDarkMode: Boolean): Uri? = if (isDarkMode) darkImageUri else lightImageUri
    
    /**
     * Récupère l'URI de la minimap pour un mode donné
     */
    fun getMinimapUri(isDarkMode: Boolean): Uri? = if (isDarkMode) darkMinimapUri else lightMinimapUri
    
    /**
     * Vérifie si la carte peut être supprimée
     */
    fun canBeDeleted(): Boolean = !isDefault
    
    /**
     * Log l'état de la carte
     */
    fun logState() {
        Logger.state(TAG, "MapItem[$name]", mapOf(
            "id" to id,
            "categoryId" to categoryId,
            "isDefault" to isDefault,
            "isBuiltIn" to isBuiltIn,
            "hasLightMode" to hasLightMode,
            "hasDarkMode" to hasDarkMode,
            "lightImageUri" to (lightImageUri?.toString() ?: "null"),
            "darkImageUri" to (darkImageUri?.toString() ?: "null"),
            "lightMinimapUri" to (lightMinimapUri?.toString() ?: "null"),
            "darkMinimapUri" to (darkMinimapUri?.toString() ?: "null")
        ))
    }
}

/**
 * Représente une catégorie de cartes
 */
data class Category(
    val id: String,        // Identifiant unique
    var name: String,      // Nom de la catégorie
    val isSystem: Boolean = false  // true pour "Sans catégorie" (non supprimable)
) {
    companion object {
        private const val TAG = "Category"
        
        const val UNCATEGORIZED_ID = "uncategorized"
        const val UNCATEGORIZED_NAME = "Sans catégorie"

        fun createUncategorized(): Category {
            Logger.d(TAG, "Creating uncategorized category")
            return Category(
                id = UNCATEGORIZED_ID,
                name = UNCATEGORIZED_NAME,
                isSystem = true
            )
        }
    }
    
    /**
     * Vérifie si la catégorie peut être supprimée
     */
    fun canBeDeleted(): Boolean = !isSystem
    
    /**
     * Log l'état de la catégorie
     */
    fun logState() {
        Logger.state(TAG, "Category[$name]", mapOf(
            "id" to id,
            "isSystem" to isSystem
        ))
    }
}

/**
 * Structure pour le stockage JSON
 */
data class MapDatabase(
    val maps: MutableList<MapItem> = mutableListOf(),
    val categories: MutableList<Category> = mutableListOf()
) {
    companion object {
        private const val TAG = "MapDatabase"
    }
    
    init {
        Logger.d(TAG, "Initializing MapDatabase")
        // S'assurer que "Sans catégorie" existe toujours
        if (categories.none { it.id == Category.UNCATEGORIZED_ID }) {
            Logger.i(TAG, "Adding uncategorized category")
            categories.add(Category.createUncategorized())
        }
        Logger.i(TAG, "Database initialized: ${maps.size} maps, ${categories.size} categories")
    }

    /**
     * Récupère toutes les cartes d'une catégorie, triées par date (récent → ancien)
     */
    fun getMapsByCategory(categoryId: String): List<MapItem> {
        Logger.entry(TAG, "getMapsByCategory", categoryId)
        val result = maps
            .filter { it.categoryId == categoryId }
            .sortedByDescending { it.dateAdded }
        Logger.exit(TAG, "getMapsByCategory", "${result.size} maps")
        return result
    }

    /**
     * Définit une carte comme carte par défaut (désactive les autres)
     */
    fun setDefaultMap(mapId: String) {
        Logger.entry(TAG, "setDefaultMap", mapId)
        val previousDefault = maps.find { it.isDefault }
        maps.forEach { it.isDefault = (it.id == mapId) }
        Logger.i(TAG, "Default map changed: ${previousDefault?.name} → ${maps.find { it.id == mapId }?.name}")
    }

    /**
     * Supprime une carte
     */
    fun removeMap(mapId: String): Boolean {
        Logger.entry(TAG, "removeMap", mapId)
        val map = maps.find { it.id == mapId }
        if (map?.isDefault == true) {
            Logger.w(TAG, "Cannot remove default map: ${map.name}")
            return false
        }
        val removed = maps.removeIf { it.id == mapId }
        Logger.i(TAG, "Map removed: $removed (${map?.name})")
        return removed
    }

    /**
     * Supprime une catégorie et déplace ses cartes vers "Sans catégorie"
     */
    fun removeCategory(categoryId: String): Boolean {
        Logger.entry(TAG, "removeCategory", categoryId)
        val category = categories.find { it.id == categoryId }
        if (category?.isSystem == true) {
            Logger.w(TAG, "Cannot remove system category: ${category.name}")
            return false
        }

        // Déplacer toutes les cartes vers "Sans catégorie"
        val affectedMaps = maps.filter { it.categoryId == categoryId }
        affectedMaps.forEach { it.categoryId = Category.UNCATEGORIZED_ID }
        Logger.i(TAG, "Moved ${affectedMaps.size} maps to uncategorized")

        val removed = categories.removeIf { it.id == categoryId }
        Logger.i(TAG, "Category removed: $removed (${category?.name})")
        return removed
    }

    /**
     * Ajoute ou met à jour une carte
     */
    fun addOrUpdateMap(map: MapItem) {
        Logger.entry(TAG, "addOrUpdateMap", map.id, map.name)
        val index = maps.indexOfFirst { it.id == map.id }
        if (index != -1) {
            Logger.d(TAG, "Updating existing map at index $index")
            maps[index] = map
        } else {
            Logger.d(TAG, "Adding new map")
            maps.add(map)
        }

        // Si c'est la carte par défaut, désactiver les autres
        if (map.isDefault) {
            setDefaultMap(map.id)
        }
        
        Logger.i(TAG, "Map ${if (index != -1) "updated" else "added"}: ${map.name}")
    }

    /**
     * Ajoute ou met à jour une catégorie
     */
    fun addOrUpdateCategory(category: Category) {
        Logger.entry(TAG, "addOrUpdateCategory", category.id, category.name)
        val index = categories.indexOfFirst { it.id == category.id }
        if (index != -1) {
            Logger.d(TAG, "Updating existing category at index $index")
            categories[index] = category
        } else {
            Logger.d(TAG, "Adding new category")
            categories.add(category)
        }
        Logger.i(TAG, "Category ${if (index != -1) "updated" else "added"}: ${category.name}")
    }
    
    /**
     * Récupère la carte par défaut
     */
    fun getDefaultMap(): MapItem? {
        val defaultMap = maps.firstOrNull { it.isDefault }
        Logger.d(TAG, "Default map: ${defaultMap?.name ?: "none"}")
        return defaultMap
    }
    
    /**
     * Récupère une carte par son ID
     */
    fun getMapById(id: String): MapItem? {
        val map = maps.find { it.id == id }
        Logger.d(TAG, "Get map by id($id): ${map?.name ?: "not found"}")
        return map
    }
    
    /**
     * Log l'état complet de la database
     */
    fun logState() {
        Logger.state(TAG, "MapDatabase", mapOf(
            "maps.size" to maps.size,
            "categories.size" to categories.size,
            "defaultMap" to (maps.firstOrNull { it.isDefault }?.name ?: "none")
        ))
    }
}

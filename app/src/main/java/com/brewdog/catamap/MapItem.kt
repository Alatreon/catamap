package com.brewdog.catamap

import android.net.Uri

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
    val isBuiltIn: Boolean = false,     // true si c'est une carte embarquée (non supprimable tant que par défaut)
    var lightMinimapUri: Uri?,         // URI de la minimap light
    var darkMinimapUri: Uri?          // URI de la minimap dark
)

/**
 * Représente une catégorie de cartes
 */
data class Category(
    val id: String,        // Identifiant unique
    var name: String,      // Nom de la catégorie
    val isSystem: Boolean = false  // true pour "Sans catégorie" (non supprimable)
) {
    companion object {
        const val UNCATEGORIZED_ID = "uncategorized"
        const val UNCATEGORIZED_NAME = "Sans catégorie"

        fun createUncategorized(): Category {
            return Category(
                id = UNCATEGORIZED_ID,
                name = UNCATEGORIZED_NAME,
                isSystem = true
            )
        }
    }
}

/**
 * Structure pour le stockage JSON
 */
data class MapDatabase(
    val maps: MutableList<MapItem> = mutableListOf(),
    val categories: MutableList<Category> = mutableListOf()
) {
    init {
        // S'assurer que "Sans catégorie" existe toujours
        if (categories.none { it.id == Category.UNCATEGORIZED_ID }) {
            categories.add(Category.createUncategorized())
        }
    }

    /**
     * Récupère toutes les cartes d'une catégorie, triées par date (récent → ancien)
     */
    fun getMapsByCategory(categoryId: String): List<MapItem> {
        return maps
            .filter { it.categoryId == categoryId }
            .sortedByDescending { it.dateAdded }
    }

    /**
     * Définit une carte comme carte par défaut (désactive les autres)
     */
    fun setDefaultMap(mapId: String) {
        maps.forEach { it.isDefault = (it.id == mapId) }
    }

    /**
     * Supprime une carte
     */
    fun removeMap(mapId: String): Boolean {
        val map = maps.find { it.id == mapId }
        if (map?.isDefault == true) {
            return false // Impossible de supprimer la carte par défaut
        }
        return maps.removeIf { it.id == mapId }
    }

    /**
     * Supprime une catégorie et déplace ses cartes vers "Sans catégorie"
     */
    fun removeCategory(categoryId: String): Boolean {
        val category = categories.find { it.id == categoryId }
        if (category?.isSystem == true) {
            return false // Impossible de supprimer "Sans catégorie"
        }

        // Déplacer toutes les cartes vers "Sans catégorie"
        maps.filter { it.categoryId == categoryId }
            .forEach { it.categoryId = Category.UNCATEGORIZED_ID }

        return categories.removeIf { it.id == categoryId }
    }

    /**
     * Ajoute ou met à jour une carte
     */
    fun addOrUpdateMap(map: MapItem) {
        val index = maps.indexOfFirst { it.id == map.id }
        if (index != -1) {
            maps[index] = map
        } else {
            maps.add(map)
        }

        // Si c'est la carte par défaut, désactiver les autres
        if (map.isDefault) {
            setDefaultMap(map.id)
        }
    }

    /**
     * Ajoute ou met à jour une catégorie
     */
    fun addOrUpdateCategory(category: Category) {
        val index = categories.indexOfFirst { it.id == category.id }
        if (index != -1) {
            categories[index] = category
        } else {
            categories.add(category)
        }
    }
}
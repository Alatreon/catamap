package com.brewdog.catamap.domain.annotation.tools

import com.brewdog.catamap.utils.logging.Logger

/**
 * Types d'outils disponibles dans le mode édition
 */
enum class ToolType {
    NONE,      // Aucun outil actif - navigation libre
    TEXT,      // Outil texte - ajouter/éditer du texte
    DRAWING,   // Outil dessin - dessiner à main levée
    ERASER;    // Outil gomme - supprimer des annotations

    companion object {
        private const val TAG = "ToolType"

        /**
         * Retourne le label d'affichage pour chaque outil
         */
        fun ToolType.getLabel(): String {
            return when (this) {
                NONE -> "Aucun outil"
                TEXT -> "Texte"
                DRAWING -> "Dessin"
                ERASER -> "Suppression"
            }
        }

        /**
         * Vérifie si l'outil permet l'affichage de la couleur
         */
        fun ToolType.showsColor(): Boolean {
            return when (this) {
                NONE, TEXT, DRAWING -> true
                ERASER -> false
            }
        }

        /**
         * Vérifie si l'outil verrouille la navigation
         */
        fun ToolType.locksNavigation(): Boolean {
            return when (this) {
                NONE -> false
                TEXT, DRAWING, ERASER -> true
            }
        }
    }
}
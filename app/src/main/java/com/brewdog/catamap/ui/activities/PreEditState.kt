package com.brewdog.catamap.ui.activities

/**
 * Sauvegarde l'état de l'interface avant l'entrée en mode édition
 * Permet de restaurer l'état exact à la sortie
 */
data class PreEditState(
    val minimapEnabled: Boolean,
    val rotateWithCompass: Boolean,
    val manualRotateEnabled: Boolean
)
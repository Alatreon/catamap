package com.brewdog.catamap.domain.annotation.models

import android.graphics.Color
import com.brewdog.catamap.utils.logging.Logger

/**
 * Représente une paire de couleurs pour les modes light et dark
 * Gestion automatique de l'inversion (identique à MapImageConverter)
 */
data class AnnotationColor(
    val lightColor: Int,
    val darkColor: Int
) {
    companion object {
        private const val TAG = "AnnotationColor"

        /**
         * Crée une paire de couleurs à partir d'une couleur de base
         *
         * @param baseColor Couleur sélectionnée par l'utilisateur
         * @param isDarkMode Mode actuel lors de la sélection
         * @return Paire light/dark avec inversion automatique
         */
        fun fromBaseColor(baseColor: Int, isDarkMode: Boolean): AnnotationColor {
            Logger.d(TAG, "Creating color pair from base: ${colorToHex(baseColor)}, isDarkMode=$isDarkMode")

            val inverted = invertColor(baseColor)

            return if (isDarkMode) {
                // Couleur choisie en mode dark → elle reste dark, on inverse pour light
                AnnotationColor(
                    lightColor = inverted,
                    darkColor = baseColor
                )
            } else {
                // Couleur choisie en mode light → elle reste light, on inverse pour dark
                AnnotationColor(
                    lightColor = baseColor,
                    darkColor = inverted
                )
            }
        }

        /**
         * Inverse une couleur (algorithme identique à MapImageConverter)
         * R' = 255 - R
         * G' = 255 - G
         * B' = 255 - B
         * Alpha préservé
         */
        private fun invertColor(color: Int): Int {
            val r = 255 - Color.red(color)
            val g = 255 - Color.green(color)
            val b = 255 - Color.blue(color)
            val a = Color.alpha(color)

            val inverted = Color.argb(a, r, g, b)
            Logger.v(TAG, "Inverted ${colorToHex(color)} → ${colorToHex(inverted)}")

            return inverted
        }

        /**
         * Convertit une couleur en format hex pour les logs
         */
        private fun colorToHex(color: Int): String {
            return String.format("#%08X", color)
        }
    }

    /**
     * Récupère la couleur appropriée selon le mode actuel
     *
     * @param isDarkMode Mode actuel de la carte
     * @return Couleur à utiliser pour le rendu
     */
    fun getColor(isDarkMode: Boolean): Int {
        return if (isDarkMode) darkColor else lightColor
    }

    /**
     * Log l'état de la couleur
     */
    fun logState() {
        Logger.state(TAG, "AnnotationColor", mapOf(
            "lightColor" to colorToHex(lightColor),
            "darkColor" to colorToHex(darkColor)
        ))
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%08X", color)
    }
}
package com.brewdog.catamap.ui.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestionnaire de l'état de l'onboarding
 * Gère la persistance et la logique de versionnement
 */
class OnboardingManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_ONBOARDING_VERSION = "onboarding_version"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        
        // Version actuelle de l'onboarding
        // Incrémenter cette valeur pour forcer un nouvel affichage
        const val CURRENT_ONBOARDING_VERSION = 1
    }

    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Vérifie si l'onboarding doit être affiché
     * @return true si l'onboarding doit être affiché
     */
    fun shouldShowOnboarding(): Boolean {
        val lastVersion = sharedPreferences.getInt(KEY_ONBOARDING_VERSION, 0)
        val completed = sharedPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        
        // Afficher si:
        // - Jamais vu (lastVersion = 0)
        // - Version plus récente disponible
        // - Non complété pour la version actuelle
        return lastVersion < CURRENT_ONBOARDING_VERSION || !completed
    }

    /**
     * Marque l'onboarding comme complété pour la version actuelle
     */
    fun markOnboardingCompleted() {
        sharedPreferences.edit()
            .putInt(KEY_ONBOARDING_VERSION, CURRENT_ONBOARDING_VERSION)
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }

    /**
     * Réinitialise l'onboarding (pour debug ou option "Revoir l'onboarding")
     */
    fun resetOnboarding() {
        sharedPreferences.edit()
            .putInt(KEY_ONBOARDING_VERSION, 0)
            .putBoolean(KEY_ONBOARDING_COMPLETED, false)
            .apply()
    }

    /**
     * Obtient la dernière version vue
     */
    fun getLastSeenVersion(): Int {
        return sharedPreferences.getInt(KEY_ONBOARDING_VERSION, 0)
    }
}

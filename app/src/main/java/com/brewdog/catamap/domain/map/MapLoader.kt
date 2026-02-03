package com.brewdog.catamap.domain.map

import android.view.View
import android.widget.FrameLayout
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.utils.logging.Logger

/**
 * Gestionnaire de chargement des cartes
 * Responsabilité : Gérer l'état de chargement, le loader UI, et coordonner le chargement
 */
class MapLoader(
    private val loadingOverlay: FrameLayout
) {

    companion object {
        private const val TAG = "MapLoader"
    }

    /**
     * États de chargement possibles
     */
    enum class LoadingState {
        IDLE,           // Rien ne se charge
        LOADING,        // Chargement en cours
        SUCCESS,        // Chargement réussi
        ERROR           // Erreur de chargement
    }

    // État actuel
    private var currentState = LoadingState.IDLE
    private var currentMap: MapItem? = null
    
    // Callbacks
    var onLoadStart: ((MapItem) -> Unit)? = null
    var onLoadSuccess: ((MapItem) -> Unit)? = null
    var onLoadError: ((MapItem, Exception) -> Unit)? = null

    init {
        Logger.i(TAG, "MapLoader initialized")
        hideLoader()
    }

    /**
     * Vérifie si un chargement est en cours
     */
    fun isLoading(): Boolean = currentState == LoadingState.LOADING

    /**
     * Récupère l'état actuel
     */
    fun getState(): LoadingState = currentState

    /**
     * Récupère la carte en cours de chargement
     */
    fun getCurrentMap(): MapItem? = currentMap

    /**
     * Démarre le chargement d'une carte
     */
    fun startLoading(map: MapItem) {
        Logger.entry(TAG, "startLoading", map.id, map.name)
        
        if (currentState == LoadingState.LOADING) {
            Logger.w(TAG, "A map is already loading: ${currentMap?.name}")
            return
        }
        
        currentState = LoadingState.LOADING
        currentMap = map
        
        showLoader()
        
        Logger.i(TAG, "Loading started for map: ${map.name}")
        onLoadStart?.invoke(map)
        
        Logger.exit(TAG, "startLoading")
    }

    /**
     * Marque le chargement comme réussi
     */
    fun onSuccess() {
        Logger.entry(TAG, "onSuccess")
        
        val map = currentMap
        if (map == null) {
            Logger.w(TAG, "No map was loading, ignoring success")
            return
        }
        
        if (currentState != LoadingState.LOADING) {
            Logger.w(TAG, "State is not LOADING, current state: $currentState")
        }
        
        currentState = LoadingState.SUCCESS
        hideLoader()
        
        Logger.i(TAG, "Loading succeeded for map: ${map.name}")
        onLoadSuccess?.invoke(map)
        
        // Reset vers IDLE après un court délai
        loadingOverlay.postDelayed({
            if (currentState == LoadingState.SUCCESS) {
                currentState = LoadingState.IDLE
                currentMap = null
                Logger.d(TAG, "State reset to IDLE")
            }
        }, 100)
        
        Logger.exit(TAG, "onSuccess")
    }

    /**
     * Marque le chargement comme échoué
     */
    fun onError(exception: Exception) {
        Logger.entry(TAG, "onError", exception.message)
        
        val map = currentMap
        if (map == null) {
            Logger.w(TAG, "No map was loading, ignoring error")
            hideLoader()
            return
        }
        
        if (currentState != LoadingState.LOADING) {
            Logger.w(TAG, "State is not LOADING, current state: $currentState")
        }
        
        currentState = LoadingState.ERROR
        hideLoader()
        
        Logger.e(TAG, "Loading failed for map: ${map.name}", exception)
        onLoadError?.invoke(map, exception)
        
        // Reset vers IDLE après un court délai
        loadingOverlay.postDelayed({
            if (currentState == LoadingState.ERROR) {
                currentState = LoadingState.IDLE
                currentMap = null
                Logger.d(TAG, "State reset to IDLE after error")
            }
        }, 500)
        
        Logger.exit(TAG, "onError")
    }

    /**
     * Annule le chargement en cours
     */
    fun cancel() {
        Logger.entry(TAG, "cancel")
        
        if (currentState != LoadingState.LOADING) {
            Logger.d(TAG, "Nothing to cancel, current state: $currentState")
            return
        }
        
        val map = currentMap
        Logger.i(TAG, "Loading cancelled for map: ${map?.name}")
        
        currentState = LoadingState.IDLE
        currentMap = null
        hideLoader()
        
        Logger.exit(TAG, "cancel")
    }

    /**
     * Affiche le loader
     */
    private fun showLoader() {
        Logger.v(TAG, "Showing loader")
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.alpha = 1f
    }

    /**
     * Cache le loader avec animation
     */
    private fun hideLoader() {
        Logger.v(TAG, "Hiding loader")
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(AppConstants.Loading.LOADER_FADE_DURATION_MS)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
            }
            .start()
    }

    /**
     * Force le masquage immédiat du loader (sans animation)
     */
    fun hideLoaderImmediate() {
        Logger.d(TAG, "Hiding loader immediately")
        loadingOverlay.clearAnimation()
        loadingOverlay.visibility = View.GONE
        loadingOverlay.alpha = 0f
    }

    /**
     * Reset l'état
     */
    fun reset() {
        Logger.entry(TAG, "reset")
        
        cancel()
        hideLoaderImmediate()
        
        Logger.i(TAG, "MapLoader reset")
        Logger.exit(TAG, "reset")
    }

    /**
     * Log l'état actuel
     */
    fun logState() {
        Logger.state(TAG, "MapLoader", mapOf(
            "currentState" to currentState,
            "currentMap" to (currentMap?.name ?: "none"),
            "loaderVisible" to (loadingOverlay.visibility == View.VISIBLE)
        ))
    }
}

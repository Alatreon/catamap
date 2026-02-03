package com.brewdog.catamap.domain.map

import android.graphics.PointF
import com.brewdog.catamap.utils.extensions.toLogString
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Classe centralisée pour gérer l'état de la carte (position, zoom, rotation)
 * Évite de devoir recalculer quoi que ce soit lors du changement Dark/Light
 */
class MapState {

    companion object {
        private const val TAG = "MapState"
    }

    /**
     * État sauvegardé de la carte
     */
    data class State(
        val scale: Float,
        val center: PointF,
        val rotation: Float
    ) {
        fun toLogString(): String = 
            "scale=${"%.3f".format(scale)}, center=${center.toLogString()}, rotation=${"%.1f".format(rotation)}°"
    }

    private var savedState: State? = null

    /**
     * Capture l'état actuel de la carte
     */
    fun capture(mapView: SubsamplingScaleImageView) {
        Logger.entry(TAG, "capture")
        
        if (!mapView.isReady) {
            Logger.w(TAG, "Cannot capture state: map not ready")
            return
        }

        val center = mapView.center
        if (center == null) {
            Logger.w(TAG, "Cannot capture state: center is null")
            return
        }

        savedState = State(
            scale = mapView.scale,
            center = PointF(center.x, center.y), // Copie pour éviter les références
            rotation = mapView.rotation
        )

        Logger.i(TAG, "State captured: ${savedState?.toLogString()}")
        Logger.exit(TAG, "capture")
    }

    /**
     * Applique l'état sauvegardé à la carte
     */
    fun apply(mapView: SubsamplingScaleImageView, animated: Boolean = false) {
        Logger.entry(TAG, "apply", animated)
        
        val state = savedState
        if (state == null) {
            Logger.w(TAG, "No saved state to apply")
            return
        }

        if (!mapView.isReady) {
            Logger.w(TAG, "Cannot apply state: map not ready")
            return
        }

        try {
            // Appliquer l'échelle et le centre
            mapView.setScaleAndCenter(state.scale, state.center)
            
            // Appliquer la rotation
            mapView.rotation = state.rotation

            Logger.i(TAG, "State applied: ${state.toLogString()}")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error applying state", e)
        }
        
        Logger.exit(TAG, "apply")
    }

    /**
     * Reset l'état sauvegardé
     */
    fun reset() {
        Logger.entry(TAG, "reset")
        
        if (savedState != null) {
            Logger.d(TAG, "Clearing saved state: ${savedState?.toLogString()}")
        } else {
            Logger.d(TAG, "No state to clear")
        }
        
        savedState = null
        Logger.i(TAG, "State reset")
        Logger.exit(TAG, "reset")
    }

    /**
     * Vérifie si un état est sauvegardé
     */
    fun hasSavedState(): Boolean {
        val has = savedState != null
        Logger.v(TAG, "hasSavedState: $has")
        return has
    }

    /**
     * Récupère l'état sauvegardé (pour inspection)
     */
    fun getSavedState(): State? = savedState

    /**
     * Log l'état actuel
     */
    fun logState() {
        Logger.state(TAG, "MapState", mapOf(
            "hasSavedState" to (savedState != null),
            "savedState" to (savedState?.toLogString() ?: "none")
        ))
    }
}

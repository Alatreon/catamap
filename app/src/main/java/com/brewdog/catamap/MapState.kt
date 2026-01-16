package com.brewdog.catamap

import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Classe centralisée pour gérer l'état de la carte (position, zoom, rotation)
 * Évite de devoir recalculer quoi que ce soit lors du changement Dark/Light
 */
class MapState {

    data class State(
        val scale: Float,
        val center: PointF,
        val rotation: Float
    )

    private var savedState: State? = null

    /**
     * Capture l'état actuel de la carte
     */
    fun capture(mapView: SubsamplingScaleImageView) {
        if (!mapView.isReady) return

        savedState = State(
            scale = mapView.scale,
            center = mapView.center?.let { PointF(it.x, it.y) } ?: PointF(0f, 0f),
            rotation = mapView.rotation
        )

        android.util.Log.d("MapState", "État capturé: scale=${savedState?.scale}, center=${savedState?.center}, rotation=${savedState?.rotation}")
    }

    /**
     * Applique l'état sauvegardé à la carte
     */
    fun apply(mapView: SubsamplingScaleImageView) {
        val state = savedState ?: return

        if (!mapView.isReady) {
            android.util.Log.w("MapState", "MapView pas prête, impossible d'appliquer l'état")
            return
        }

        mapView.setScaleAndCenter(state.scale, state.center)
        mapView.rotation = state.rotation

        android.util.Log.d("MapState", "État appliqué: scale=${state.scale}, center=${state.center}, rotation=${state.rotation}")
    }
}
package com.brewdog.catamap

import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Gestionnaire centralisé de l'état des cartes pour garantir la cohérence
 * entre les transitions light/dark et les changements de carte
 *
 * VERSION FINALE CORRIGÉE : Préserve scale, position ET minScale lors du switch
 */
class MapStateManager {

    /**
     * État complet d'une carte à un instant T
     */
    data class MapState(
        val scale: Float = 1f,
        val centerX: Float = 0f,
        val centerY: Float = 0f,
        val rotation: Float = 0f,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val minScale: Float = 1f,
        val maxScale: Float = 10f
    ) {
        /**
         * Convertit les coordonnées pour une image de dimensions différentes
         */
        fun convertToNewDimensions(newWidth: Int, newHeight: Int): MapState {
            if (imageWidth == 0 || imageHeight == 0 || newWidth == 0 || newHeight == 0) {
                return this
            }

            if (imageWidth == newWidth && imageHeight == newHeight) {
                return this
            }

            val widthRatio = newWidth.toFloat() / imageWidth.toFloat()
            val heightRatio = newHeight.toFloat() / imageHeight.toFloat()

            val newCenterX = centerX * widthRatio
            val newCenterY = centerY * heightRatio

            return copy(
                centerX = newCenterX,
                centerY = newCenterY,
                imageWidth = newWidth,
                imageHeight = newHeight
            )
        }

        fun isValid(): Boolean {
            return imageWidth > 0 &&
                    imageHeight > 0 &&
                    scale > 0 &&
                    minScale > 0 &&
                    maxScale > 0 &&
                    !centerX.isNaN() &&
                    !centerY.isNaN() &&
                    !scale.isNaN() &&
                    !rotation.isNaN() &&
                    !minScale.isNaN() &&
                    !maxScale.isNaN()
        }
    }

    private var currentState: MapState = MapState()

    fun captureState(mapView: SubsamplingScaleImageView): MapState {
        if (!mapView.isReady) {
            return currentState
        }

        val center = mapView.center ?: PointF(0f, 0f)

        currentState = MapState(
            scale = mapView.scale,
            centerX = center.x,
            centerY = center.y,
            rotation = mapView.rotation,
            imageWidth = mapView.sWidth,
            imageHeight = mapView.sHeight,
            minScale = mapView.minScale,
            maxScale = mapView.maxScale
        )

        return currentState
    }

    fun applyState(
        mapView: SubsamplingScaleImageView,
        state: MapState? = null,
        animated: Boolean = false
    ) {
        val targetState = state ?: currentState

        if (!mapView.isReady || !targetState.isValid()) {
            mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    applyStateInternal(mapView, targetState, animated)
                }
                override fun onImageLoaded() {}
                override fun onPreviewLoadError(e: Exception?) {}
                override fun onImageLoadError(e: Exception?) {}
                override fun onTileLoadError(e: Exception?) {}
                override fun onPreviewReleased() {}
            })
            return
        }

        applyStateInternal(mapView, targetState, animated)
    }

    private fun applyStateInternal(
        mapView: SubsamplingScaleImageView,
        state: MapState,
        animated: Boolean
    ) {
        // Vérifier si c'est la même carte (dimensions identiques)
        val sameDimensions = (mapView.sWidth == state.imageWidth &&
                mapView.sHeight == state.imageHeight)

        if (sameDimensions) {
            // ✅ MÊME CARTE : Restaurer les limites de zoom EXACTES
            // Ceci est crucial pour éviter que adjustMapForRotation ne crée des incohérences
            mapView.setMinScale(state.minScale)
            mapView.setMaxScale(state.maxScale)

            // Forcer également via les propriétés (double sécurité)
            mapView.minScale = state.minScale
            mapView.maxScale = state.maxScale
        }

        // Convertir les coordonnées si nécessaire
        val convertedState = if (!sameDimensions) {
            state.convertToNewDimensions(mapView.sWidth, mapView.sHeight)
        } else {
            state
        }

        // Valider le centre
        val finalCenter = PointF(
            convertedState.centerX.coerceIn(0f, mapView.sWidth.toFloat()),
            convertedState.centerY.coerceIn(0f, mapView.sHeight.toFloat())
        )

        // 🔧 CORRECTION CRITIQUE : Ne pas coercer le scale pour la même carte
        val finalScale = if (sameDimensions) {
            // ✅ Même carte : Utiliser le scale EXACT capturé (pas de coerce!)
            convertedState.scale
        } else {
            // Carte différente : Vérifier que le scale est dans les limites
            convertedState.scale.coerceIn(mapView.minScale, mapView.maxScale)
        }

        // Appliquer scale et position
        if (animated) {
            mapView.animateScaleAndCenter(finalScale, finalCenter)
                ?.withDuration(150)
                ?.start()
        } else {
            mapView.setScaleAndCenter(finalScale, finalCenter)
        }

        // Appliquer la rotation
        mapView.rotation = state.rotation
    }

    fun applyStateWithDimensionConversion(
        mapView: SubsamplingScaleImageView,
        sourceState: MapState,
        animated: Boolean = false
    ) {
        if (!mapView.isReady) {
            mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    applyStateWithDimensionConversion(mapView, sourceState, animated)
                }
                override fun onImageLoaded() {}
                override fun onPreviewLoadError(e: Exception?) {}
                override fun onImageLoadError(e: Exception?) {}
                override fun onTileLoadError(e: Exception?) {}
                override fun onPreviewReleased() {}
            })
            return
        }

        // Convertir si dimensions différentes
        val convertedState = if (mapView.sWidth != sourceState.imageWidth ||
            mapView.sHeight != sourceState.imageHeight) {
            sourceState.convertToNewDimensions(mapView.sWidth, mapView.sHeight)
        } else {
            sourceState
        }

        applyState(mapView, convertedState, animated)
    }

    fun resetState(mapView: SubsamplingScaleImageView) {
        if (!mapView.isReady) {
            mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    resetState(mapView)
                }
                override fun onImageLoaded() {}
                override fun onPreviewLoadError(e: Exception?) {}
                override fun onImageLoadError(e: Exception?) {}
                override fun onTileLoadError(e: Exception?) {}
                override fun onPreviewReleased() {}
            })
            return
        }

        mapView.resetScaleAndCenter()
        mapView.rotation = 0f
        captureState(mapView)
    }

    fun synchronizeViews(
        sourceView: SubsamplingScaleImageView,
        targetView: SubsamplingScaleImageView,
        animated: Boolean = false
    ) {
        val state = captureState(sourceView)
        applyStateWithDimensionConversion(targetView, state, animated)
    }

    fun getCurrentState(): MapState = currentState

    fun setCurrentState(state: MapState) {
        if (state.isValid()) {
            currentState = state
        }
    }

    fun logCurrentState(tag: String = "MapStateManager") {
        android.util.Log.d(tag, """
            Current State:
            - Scale: ${currentState.scale}
            - Center: (${currentState.centerX}, ${currentState.centerY})
            - Rotation: ${currentState.rotation}°
            - Dimensions: ${currentState.imageWidth}x${currentState.imageHeight}
            - MinScale: ${currentState.minScale}
            - MaxScale: ${currentState.maxScale}
        """.trimIndent())
    }

    fun logViewState(mapView: SubsamplingScaleImageView, tag: String = "MapStateManager") {
        if (!mapView.isReady) {
            android.util.Log.d(tag, "View not ready")
            return
        }

        val center = mapView.center ?: PointF(0f, 0f)
        android.util.Log.d(tag, """
            View State:
            - Scale: ${mapView.scale} (min: ${mapView.minScale}, max: ${mapView.maxScale})
            - Center: (${center.x}, ${center.y})
            - Rotation: ${mapView.rotation}°
            - Dimensions: ${mapView.sWidth}x${mapView.sHeight}
        """.trimIndent())
    }
}
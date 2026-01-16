package com.brewdog.catamap

import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Contrôleur central de la minimap
 * Gère la synchronisation entre la carte principale et la minimap
 */
class MinimapController(
    private val minimapView: MinimapView,
    private val mainMapView: SubsamplingScaleImageView
) {

    // PARAMÈTRES CONFIGURABLES
    companion object {
        private const val UPDATE_THROTTLE_MS = 66L  // ~15fps
    }

    // État
    private var isEnabled = false
    private var currentMapWidth = 0
    private var currentMapHeight = 0
    private var minimapWidth = 0
    private var minimapHeight = 0
    private var lastUpdateTime = 0L

    init {
        // Écouter les drags sur la minimap
        minimapView.onViewportDragged = { dx, dy ->
            handleViewportDrag(dx, dy)
        }
    }

    /**
     * Active ou désactive la minimap
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        minimapView.visibility = if (enabled) View.VISIBLE else View.GONE

        if (enabled) {
            updateViewport()
        }
    }

    /**
     * Charge l'image de la minimap
     */
    fun loadMinimapImage(uri: Uri?) {
        minimapView.setMinimapImage(uri)

        // Obtenir les dimensions de la minimap après chargement
        minimapView.post {
            minimapWidth = minimapView.width
            minimapHeight = minimapView.height

            android.util.Log.d("MinimapController", "Minimap dimensions: ${minimapWidth}x${minimapHeight}")

            // 🔧 FIX : Attendre que l'image soit vraiment chargée
            minimapView.postDelayed({
                if (mainMapView.isReady) {
                    currentMapWidth = mainMapView.sWidth
                    currentMapHeight = mainMapView.sHeight
                    updateViewport()
                    android.util.Log.d("MinimapController", "Viewport updated after image load")
                }
            }, 850)  // Court délai pour s'assurer que l'ImageView a chargé l'image
        }
    }

    /**
     * Met à jour le viewport (appelé lors de pan/zoom)
     */
    fun updateViewport() {
        if (!isEnabled) return
        if (!mainMapView.isReady) return

        // Throttle des updates (15fps max)
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_THROTTLE_MS) return
        lastUpdateTime = now

        // Dimensions de la carte principale
        currentMapWidth = mainMapView.sWidth
        currentMapHeight = mainMapView.sHeight

        if (currentMapWidth == 0 || currentMapHeight == 0) return
        if (minimapWidth == 0 || minimapHeight == 0) return

        // Calculer le viewport
        val viewport = calculateViewportRect()
        minimapView.setViewport(viewport)
    }

    /**
     * Calcule le rectangle du viewport sur la minimap
     */
    private fun calculateViewportRect(): RectF {
        // Centre visible sur la carte principale (coordonnées image)
        val center = mainMapView.center ?: PointF(
            currentMapWidth / 2f,
            currentMapHeight / 2f
        )

        // Échelle actuelle
        val scale = mainMapView.scale

        // Dimensions de l'écran
        val viewWidth = mainMapView.width.toFloat()
        val viewHeight = mainMapView.height.toFloat()

        // Dimensions visibles sur la carte (coordonnées image)
        val visibleWidth = viewWidth / scale
        val visibleHeight = viewHeight / scale

        // Coins du viewport (coordonnées image)
        val left = center.x - visibleWidth / 2f
        val top = center.y - visibleHeight / 2f
        val right = center.x + visibleWidth / 2f
        val bottom = center.y + visibleHeight / 2f

        // Ratio minimap / carte
        val ratioX = minimapWidth.toFloat() / currentMapWidth
        val ratioY = minimapHeight.toFloat() / currentMapHeight

        // Convertir en coordonnées minimap
        val minimapLeft = left * ratioX
        val minimapTop = top * ratioY
        val minimapRight = right * ratioX
        val minimapBottom = bottom * ratioY

        return RectF(minimapLeft, minimapTop, minimapRight, minimapBottom)
    }

    /**
     * Gère le drag du viewport sur la minimap
     */
    private fun handleViewportDrag(dx: Float, dy: Float) {
        if (!isEnabled) return
        if (!mainMapView.isReady) return

        // Ratio minimap / carte
        val ratioX = currentMapWidth.toFloat() / minimapWidth
        val ratioY = currentMapHeight.toFloat() / minimapHeight

        // Convertir le delta minimap en delta carte
        val mapDx = dx * ratioX
        val mapDy = dy * ratioY

        // Centre actuel
        val currentCenter = mainMapView.center ?: return

        // Nouveau centre (déplacement absolu)
        val newCenter = PointF(
            (currentCenter.x + mapDx).coerceIn(0f, currentMapWidth.toFloat()),
            (currentCenter.y + mapDy).coerceIn(0f, currentMapHeight.toFloat())
        )

        // Appliquer le nouveau centre
        mainMapView.setScaleAndCenter(mainMapView.scale, newCenter)

        // Mettre à jour le viewport immédiatement (pas de throttle pour le drag)
        lastUpdateTime = 0L
        updateViewport()
    }
}
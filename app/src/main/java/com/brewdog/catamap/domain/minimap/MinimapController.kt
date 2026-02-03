package com.brewdog.catamap.domain.minimap

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.view.View
import com.brewdog.catamap.ui.adapters.MinimapView
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.utils.extensions.*
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Contrôleur central de la minimap
 * Gère la synchronisation entre la carte principale et la minimap
 */
class MinimapController(
    private val context: Context,
    private val minimapView: MinimapView,
    private val mainMapView: SubsamplingScaleImageView
) {

    companion object {
        private const val TAG = "MinimapController"
    }

    // État
    private var isEnabled = false
    private var currentMapWidth = 0
    private var currentMapHeight = 0
    private var minimapWidth = 0
    private var minimapHeight = 0
    private var lastUpdateTime = 0L

    init {
        Logger.i(TAG, "MinimapController initialized")
        setupMinimapCallbacks()
    }

    /**
     * Configure les callbacks de la minimap
     */
    private fun setupMinimapCallbacks() {
        Logger.d(TAG, "Setting up minimap callbacks")
        
        minimapView.onViewportDragged = { dx, dy ->
            Logger.v(TAG, "Viewport dragged: dx=$dx, dy=$dy")
            handleViewportDrag(dx, dy)
        }
    }

    /**
     * Active ou désactive la minimap
     */
    fun setEnabled(enabled: Boolean) {
        Logger.entry(TAG, "setEnabled", enabled)
        
        if (isEnabled == enabled) {
            Logger.d(TAG, "Minimap already ${if (enabled) "enabled" else "disabled"}")
            return
        }
        
        isEnabled = enabled
        minimapView.visibility = if (enabled) View.VISIBLE else View.GONE

        if (enabled) {
            Logger.i(TAG, "Minimap enabled, updating viewport")
            updateViewport()
        } else {
            Logger.i(TAG, "Minimap disabled")
        }
        
        Logger.exit(TAG, "setEnabled")
    }

    /**
     * Vérifie si la minimap est activée
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Charge l'image de la minimap
     */
    fun loadMinimapImage(uri: Uri?) {
        Logger.entry(TAG, "loadMinimapImage", uri?.toString())
        
        if (uri == null) {
            Logger.w(TAG, "Minimap URI is null, clearing image")
            minimapView.setMinimapImage(null)
            return
        }
        
        minimapView.setMinimapImage(uri)
        Logger.i(TAG, "Minimap image set")

        // Obtenir les dimensions de la minimap après qu'elle soit chargée
        minimapView.post {
            minimapWidth = minimapView.width
            minimapHeight = minimapView.height

            Logger.d(TAG, "Minimap dimensions: ${minimapWidth}x${minimapHeight}")

            // Forcer une mise à jour immédiate
            mainMapView.post {
                if (mainMapView.isReady) {
                    currentMapWidth = mainMapView.sWidth
                    currentMapHeight = mainMapView.sHeight
                    Logger.d(TAG, "Main map dimensions: ${currentMapWidth}x${currentMapHeight}")
                    updateViewport()
                } else {
                    Logger.w(TAG, "Main map not ready yet")
                }
            }
        }
        
        Logger.exit(TAG, "loadMinimapImage")
    }

    /**
     * Met à jour la rotation de la minimap
     */
    fun updateRotation(degrees: Float) {
        Logger.v(TAG, "updateRotation: $degrees°")
        
        if (!isEnabled) {
            Logger.v(TAG, "Minimap disabled, skipping rotation update")
            return
        }

        updateViewport()
    }

    /**
     * Met à jour le viewport (appelé lors de pan/zoom)
     */
    fun updateViewport() {
        Logger.v(TAG, "updateViewport called")
        
        if (!isEnabled) {
            Logger.v(TAG, "Minimap disabled, skipping viewport update")
            return
        }
        
        if (!mainMapView.isReady) {
            Logger.v(TAG, "Main map not ready, skipping viewport update")
            return
        }

        // Throttle des updates pour éviter surcharge
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < AppConstants.Minimap.UPDATE_THROTTLE_MS) {
            Logger.v(TAG, "Throttling viewport update (${now - lastUpdateTime}ms since last)")
            return
        }
        lastUpdateTime = now

        // Dimensions de la carte principale
        currentMapWidth = mainMapView.sWidth
        currentMapHeight = mainMapView.sHeight

        if (currentMapWidth == 0 || currentMapHeight == 0) {
            Logger.w(TAG, "Invalid map dimensions: ${currentMapWidth}x${currentMapHeight}")
            return
        }
        
        if (minimapWidth == 0 || minimapHeight == 0) {
            Logger.w(TAG, "Invalid minimap dimensions: ${minimapWidth}x${minimapHeight}")
            return
        }

        // Calculer le viewport
        val viewport = calculateViewportRect()
        minimapView.setViewport(viewport)
        
        Logger.v(TAG, "Viewport updated: left=${viewport.left}, top=${viewport.top}, right=${viewport.right}, bottom=${viewport.bottom}")
    }

    /**
     * Calcule le rectangle du viewport sur la minimap
     */
    private fun calculateViewportRect(): RectF {
        Logger.v(TAG, "calculateViewportRect called")
        
        // Centre visible sur la carte principale (coordonnées image)
        val center = mainMapView.center ?: run {
            val defaultCenter = PointF(currentMapWidth / 2f, currentMapHeight / 2f)
            Logger.w(TAG, "Center is null, using default: ${defaultCenter.toLogString()}")
            defaultCenter
        }

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

        Logger.v(TAG, "Viewport calculated: " +
                "center=${center.toLogString()}, " +
                "scale=$scale, " +
                "visible=${visibleWidth}x${visibleHeight}, " +
                "ratio=${ratioX}x${ratioY}")

        return RectF(minimapLeft, minimapTop, minimapRight, minimapBottom)
    }

    /**
     * Gère le drag du viewport sur la minimap
     */
    private fun handleViewportDrag(dx: Float, dy: Float) {
        Logger.entry(TAG, "handleViewportDrag", dx, dy)
        
        if (!isEnabled) {
            Logger.v(TAG, "Minimap disabled, ignoring drag")
            return
        }
        
        if (!mainMapView.isReady) {
            Logger.w(TAG, "Main map not ready, ignoring drag")
            return
        }

        // Ratio minimap / carte
        val ratioX = currentMapWidth.toFloat() / minimapWidth
        val ratioY = currentMapHeight.toFloat() / minimapHeight

        // Convertir le delta minimap en delta carte
        val mapDx = dx * ratioX
        val mapDy = dy * ratioY

        Logger.d(TAG, "Drag converted: minimap($dx, $dy) -> map($mapDx, $mapDy), ratio=($ratioX, $ratioY)")

        // Centre actuel
        val currentCenter = mainMapView.center
        if (currentCenter == null) {
            Logger.w(TAG, "Current center is null, cannot drag")
            return
        }

        // Nouveau centre (déplacement absolu)
        val newCenter = PointF(
            (currentCenter.x + mapDx).coerceIn(0f, currentMapWidth.toFloat()),
            (currentCenter.y + mapDy).coerceIn(0f, currentMapHeight.toFloat())
        )

        Logger.d(TAG, "New center: ${currentCenter.toLogString()} -> ${newCenter.toLogString()}")

        // Appliquer le nouveau centre
        mainMapView.setScaleAndCenter(mainMapView.scale, newCenter)

        // Mettre à jour le viewport immédiatement (pas de throttle pour le drag)
        lastUpdateTime = 0L
        updateViewport()
        
        Logger.exit(TAG, "handleViewportDrag")
    }

    /**
     * Définit les dimensions de la carte chargée
     */
    fun setMapDimensions(width: Int, height: Int) {
        Logger.entry(TAG, "setMapDimensions", width, height)
        
        currentMapWidth = width
        currentMapHeight = height
        
        Logger.i(TAG, "Map dimensions set: ${width}x${height}")
        updateViewport()
        
        Logger.exit(TAG, "setMapDimensions")
    }

    /**
     * Reset l'état
     */
    fun reset() {
        Logger.entry(TAG, "reset")
        
        lastUpdateTime = 0L
        currentMapWidth = 0
        currentMapHeight = 0
        
        Logger.i(TAG, "MinimapController reset")
        Logger.exit(TAG, "reset")
    }

    fun forceUpdateViewport() {
        if (!isEnabled) return
        if (!mainMapView.isReady) return
        currentMapWidth = mainMapView.sWidth
        currentMapHeight = mainMapView.sHeight
        if (currentMapWidth == 0 || currentMapHeight == 0) return
        if (minimapWidth == 0 || minimapHeight == 0) return
        val viewport = calculateViewportRect()
        minimapView.setViewport(viewport)
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        Logger.entry(TAG, "cleanup")
        
        minimapView.onViewportDragged = null
        
        Logger.i(TAG, "MinimapController cleaned up")
        Logger.exit(TAG, "cleanup")
    }

    /**
     * Log l'état actuel
     */
    fun logState() {
        Logger.state(TAG, "MinimapController", mapOf(
            "isEnabled" to isEnabled,
            "currentMapDimensions" to "${currentMapWidth}x${currentMapHeight}",
            "minimapDimensions" to "${minimapWidth}x${minimapHeight}",
            "lastUpdateTime" to lastUpdateTime
        ))
    }
}

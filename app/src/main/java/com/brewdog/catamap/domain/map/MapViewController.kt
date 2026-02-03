package com.brewdog.catamap.domain.map

import android.content.Context
import android.graphics.PointF
import android.view.ViewGroup
import android.widget.FrameLayout
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.domain.minimap.MinimapController
import com.brewdog.catamap.utils.extensions.*
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Contrôleur pour gérer l'affichage et les interactions avec la carte principale
 * Responsabilité : Chargement, affichage, zoom, rotation, pan
 */
class MapViewController(
    private val context: Context,
    private val mapView: SubsamplingScaleImageView
) {

    companion object {
        private const val TAG = "MapViewController"
    }

    // État de la carte
    private val mapState = MapState()
    private var currentMap: MapItem? = null
    private var isDarkMode = true
    private var isMapAdjusted = false
    private var adjustRetryCount = 0
    private var mapExecutor: ExecutorService? = null
    private var isModeSwitching = false
    private var isRotationEnabled = false
    private var currentRotation = 0f
    // Callbacks
    var onMapReady: (() -> Unit)? = null
    var onMapLoadError: ((Exception) -> Unit)? = null
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var rotationPaddingApplied = false

    init {
        Logger.i(TAG, "MapViewController initialized")
        setupMapView()
        setupMapExecutor()
    }

    /**
     * Configure la vue de la carte
     */
    private fun setupMapView() {
        Logger.entry(TAG, "setupMapView")

        mapView.apply {
            isPanEnabled = true
            isZoomEnabled = true
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
            setTileBackgroundColor(android.graphics.Color.TRANSPARENT)
            rotation = AppConstants.Map.DEFAULT_ROTATION
            // Permettre la rotation manuelle
            isZoomEnabled = true
            isPanEnabled = true
            isQuickScaleEnabled = true

            Logger.d(TAG, "MapView configured: maxScale=${AppConstants.Map.MAX_SCALE}, rotation=${AppConstants.Map.DEFAULT_ROTATION}")

        }

        setupImageEventListener()
        Logger.exit(TAG, "setupMapView")
    }

    /**
     * Configure le listener d'événements de l'image
     */
    private fun setupImageEventListener() {
        Logger.d(TAG, "Setting up image event listener")

        mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                Logger.entry(TAG, "onImageEventListener.onReady")
                handleImageReady()
                Logger.exit(TAG, "onImageEventListener.onReady")
            }

            override fun onImageLoaded() {
                Logger.d(TAG, "Image loaded completely")
            }

            override fun onPreviewLoadError(e: Exception?) {
                Logger.e(TAG, "Preview load error", e)
                onMapLoadError?.invoke(e ?: Exception("Preview load error"))
            }

            override fun onImageLoadError(e: Exception?) {
                Logger.e(TAG, "Image load error", e)
                onMapLoadError?.invoke(e ?: Exception("Image load error"))
            }

            override fun onTileLoadError(e: Exception?) {
                Logger.w(TAG, "Tile load error", e)
            }

            override fun onPreviewReleased() {
                Logger.v(TAG, "Preview released")
            }
        })
    }

    /**
     * Gère l'événement "image prête"
     */
    private fun handleImageReady() {
        if (!mapView.isReady) {
            Logger.w(TAG, "handleImageReady called but map not ready")
            return
        }

        Logger.d(TAG, "Map ready: size=${mapView.sWidth}x${mapView.sHeight}")

        mapView.post {
            // Calculer et appliquer l'échelle minimale
            //val minScale = calculateMinScaleWithPadding()
            //mapView.minScale = minScale
            //Logger.d(TAG, "Min scale applied: $minScale")

            // Ajuster la carte pour la rotation si nécessaire
            if (!isMapAdjusted) {
                adjustMapForRotation()
            }

            // Restaurer l'état sauvegardé
            mapView.postDelayed({
                if (mapState.hasSavedState()) {
                    Logger.d(TAG, "Restoring map state")
                    mapState.apply(mapView)
                } else {
                    Logger.d(TAG, "No saved state, centering map")
                    // centerMap(minScale)  // ← Appel à centerMap()
                }

                onMapReady?.invoke()
            }, AppConstants.Loading.MAP_READY_DELAY_MS)
        }

    }

    /**
     * Calcule l'échelle minimale avec padding
     */
    private fun calculateMinScaleWithPadding(): Float {
        Logger.entry(TAG, "calculateMinScaleWithPadding")

        if (!mapView.isReady) {
            Logger.w(TAG, "MapView not ready, returning default scale")
            return 0.5f
        }

        val imageWidth = mapView.sWidth.toFloat()
        val imageHeight = mapView.sHeight.toFloat()
        val viewWidth = mapView.width.toFloat()
        val viewHeight = mapView.height.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            Logger.w(TAG, "Invalid dimensions: image=${imageWidth}x${imageHeight}, view=${viewWidth}x${viewHeight}")
            return 0.5f
        }

        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val minScale = minOf(scaleX, scaleY) * 0.95f

        Logger.d(TAG, "Calculated minScale: $minScale (scaleX=$scaleX, scaleY=$scaleY)")
        Logger.exit(TAG, "calculateMinScaleWithPadding", minScale)

        return minScale
    }

    /**
     * Configure le pool de threads optimal pour le décodage des tuiles
     */
    private fun setupMapExecutor() {
        Logger.entry(TAG, "setupMapExecutor")

        val cpuCount = Runtime.getRuntime().availableProcessors()
        val threadCount = minOf(cpuCount - 1, AppConstants.Map.MAX_DECODER_THREADS)
            .coerceAtLeast(AppConstants.Map.MIN_DECODER_THREADS)

        Logger.i(TAG, "CPU cores: $cpuCount → Using $threadCount decoder threads")

        val threadCounter = java.util.concurrent.atomic.AtomicInteger(0)

        mapExecutor = Executors.newFixedThreadPool(threadCount) { runnable ->
            Thread(runnable).apply {
                priority = Thread.NORM_PRIORITY + 1
                name = "MapTileDecoder-${threadCounter.incrementAndGet()}"
                isDaemon = true
            }

        }

        mapView.setExecutor(mapExecutor as Executor)

        Logger.i(TAG, "Map executor configured with $threadCount threads")
        Logger.exit(TAG, "setupMapExecutor")
    }

    /**
     * Ajuste la carte pour la rotation
     */
    private fun adjustMapForRotation() {
        Logger.entry(TAG, "adjustMapForRotation", "retry=$adjustRetryCount")

        if (!mapView.isReady) {
            Logger.w(TAG, "MapView not ready for adjustment")
            return
        }

        if (adjustRetryCount >= AppConstants.Loading.MAX_ADJUST_RETRIES) {
            Logger.w(TAG, "Max adjust retries reached ($adjustRetryCount)")
            isMapAdjusted = true
            return
        }

        val imageWidth = mapView.sWidth.toFloat()
        val imageHeight = mapView.sHeight.toFloat()
        val viewWidth = mapView.width.toFloat()
        val viewHeight = mapView.height.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0) {
            Logger.w(TAG, "Invalid image dimensions, retrying...")
            adjustRetryCount++
            mapView.postDelayed({ adjustMapForRotation() }, 50)
            return
        }

        val aspectRatio = imageWidth / imageHeight
        val targetScale = if (aspectRatio > 1) {
            viewHeight / imageHeight
        } else {
            viewWidth / imageWidth
        }

        val center = PointF(imageWidth / 2f, imageHeight / 2f)
        mapView.setScaleAndCenter(targetScale, center)

        isMapAdjusted = true
        adjustRetryCount = 0

        Logger.i(TAG, "Map adjusted: aspectRatio=$aspectRatio, scale=$targetScale, center=${center.toLogString()}")
        Logger.exit(TAG, "adjustMapForRotation")
    }

    /**
     * Centre la carte
     */
    private fun centerMap(scale: Float) {
        Logger.entry(TAG, "centerMap", scale)

        if (!mapView.isReady) {
            Logger.w(TAG, "Cannot center: map not ready")
            return
        }

        val center = PointF(
            mapView.sWidth / 2f,
            mapView.sHeight / 2f
        )

        mapView.setScaleAndCenter(scale, center)
        Logger.i(TAG, "Map centered at ${center.toLogString()} with scale $scale")
        Logger.exit(TAG, "centerMap")
    }

    /**
     * Charge une carte
     */
    fun loadMap(map: MapItem, darkMode: Boolean) {
        Logger.entry(TAG, "loadMap", map.id, map.name, darkMode)

        val isNewMap = currentMap?.id != map.id

        currentMap = map
        isDarkMode = darkMode

        if (!isModeSwitching) {
            isMapAdjusted = false
            adjustRetryCount = 0
        }

        // Sauvegarder l'état actuel si la carte est prête
        if (mapView.isReady && !isModeSwitching) {
            Logger.d(TAG, "Capturing current map state before loading new map")
            mapState.capture(mapView)
        }

        // Choisir la bonne image
        val imageSource = getImageSource(map, darkMode)
        if (imageSource == null) {
            Logger.e(TAG, "No image source available for map: ${map.name}")
            onMapLoadError?.invoke(Exception("No image available"))
            return
        }

        // Charger l'image
        try {
            mapView.setImage(imageSource)
            Logger.i(TAG, "Image loading started for map: ${map.name} (${if (darkMode) "dark" else "light"} mode)")
            // Ajouter listener pour nouvelle carte OU mode switch
            if (isNewMap || isModeSwitching) {
                mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                    override fun onReady() {
                        if (isNewMap) {
                            val currentScale = mapView.scale
                            centerMap(currentScale)
                        }

                        if (isModeSwitching) {
                            mapState.apply(mapView, animated = false)
                            isModeSwitching = false
                            Logger.i(TAG, "Map state restored after mode switch")
                        }

                        // Appeler le callback
                        onMapReady?.invoke()
                        Logger.d(TAG, "onMapReady called")

                        // Retirer le listener
                        mapView.setOnImageEventListener(null)
                    }

                    override fun onImageLoaded() {}
                    override fun onPreviewLoadError(e: Exception?) {}
                    override fun onImageLoadError(e: Exception?) {}
                    override fun onTileLoadError(e: Exception?) {}
                    override fun onPreviewReleased() {}
                })
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load image", e)
            onMapLoadError?.invoke(e)
        }

        Logger.exit(TAG, "loadMap")
    }

    /**
     * Change le mode (clair/sombre)
     */
    fun switchMode(darkMode: Boolean) {
        Logger.entry(TAG, "switchMode", darkMode)

        val map = currentMap
        if (map == null) {
            Logger.w(TAG, "No current map to switch mode")
            return
        }

        if (isDarkMode == darkMode) {
            Logger.d(TAG, "Already in ${if (darkMode) "dark" else "light"} mode")
            return
        }

        // Activer le flag de changement de mode
        isModeSwitching = true

        // Sauvegarder l'état
        if (mapView.isReady) {
            mapState.capture(mapView)
            Logger.d(TAG, "Map state captured before mode switch: ${mapState.getSavedState()?.toLogString()}")
        }

        isDarkMode = darkMode

        // Changer la couleur de fond
        val backgroundColor = if (darkMode) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
        mapView.setBackgroundColor(backgroundColor)

        // Recharger l'image (loadMap verifie isModeSwitching)
        loadMap(map, darkMode)

        Logger.i(TAG, "Mode switched to ${if (darkMode) "dark" else "light"}")
        Logger.exit(TAG, "switchMode")
    }

    /**
     * Récupère la source d'image appropriée
     */
    private fun getImageSource(map: MapItem, darkMode: Boolean): ImageSource? {
        Logger.entry(TAG, "getImageSource", map.id, darkMode)

        val imageSource = when {
            map.isBuiltIn -> {
                // TODO: Remplacer par les vrais drawable IDs
                val drawableId = if (darkMode) {
                    android.R.drawable.ic_menu_gallery // Placeholder
                } else {
                    android.R.drawable.ic_menu_gallery // Placeholder
                }
                Logger.d(TAG, "Using built-in drawable for map: ${map.name}")
                ImageSource.resource(drawableId)
            }
            darkMode && map.hasDarkMode -> {
                val uri = map.darkImageUri
                if (uri != null) {
                    Logger.d(TAG, "Using dark mode URI: $uri")
                    ImageSource.uri(uri)
                } else {
                    Logger.e(TAG, "Dark mode requested but darkImageUri is null")
                    null
                }
            }
            !darkMode && map.hasLightMode -> {
                val uri = map.lightImageUri
                if (uri != null) {
                    Logger.d(TAG, "Using light mode URI: $uri")
                    ImageSource.uri(uri)
                } else {
                    Logger.e(TAG, "Light mode requested but lightImageUri is null")
                    null
                }
            }
            map.hasDarkMode -> {
                val uri = map.darkImageUri
                if (uri != null) {
                    Logger.d(TAG, "Fallback to dark mode URI: $uri")
                    ImageSource.uri(uri)
                } else {
                    Logger.e(TAG, "Fallback to dark mode but darkImageUri is null")
                    null
                }
            }
            map.hasLightMode -> {
                val uri = map.lightImageUri
                if (uri != null) {
                    Logger.d(TAG, "Fallback to light mode URI: $uri")
                    ImageSource.uri(uri)
                } else {
                    Logger.e(TAG, "Fallback to light mode but lightImageUri is null")
                    null
                }
            }
            else -> {
                Logger.e(TAG, "No image available for map: ${map.name}")
                null
            }
        }

        Logger.exit(TAG, "getImageSource", imageSource != null)
        return imageSource
    }

    /**
     * Rotation de la carte avec gestion des marges
     */
    fun setRotation(angle: Float, rotationEnabled: Boolean) {
        Logger.v(TAG, "setRotation: $angle°, enabled=$rotationEnabled")

        currentRotation = angle
        isRotationEnabled = rotationEnabled

        mapView.rotation = angle

        // Ajuster les marges virtuelles selon l'état de rotation
        if (rotationEnabled && angle != 0f) {
            applyRotationPadding()
        } else {
            removeRotationPadding()
        }
    }

    /**
     * Applique un padding virtuel pour éviter les coins noirs lors de la rotation
     */
    private fun applyRotationPadding() {
        Logger.entry(TAG, "applyRotationPadding")

        if (!mapView.isReady) {
            Logger.w(TAG, "Map not ready, cannot apply rotation padding")
            return
        }

        if (rotationPaddingApplied) {
            Logger.d(TAG, "Rotation padding already applied, skipping")
            return
        }

        val viewWidth = mapView.width
        val viewHeight = mapView.height

        if (viewWidth == 0 || viewHeight == 0) {
            Logger.w(TAG, "Map view has zero dimensions, retrying...")
            mapView.postDelayed({ applyRotationPadding() }, 50)
            return
        }

        //  SAUVEGARDER APRÈS toutes les vérifications (map est ready ET dimensions > 0)
        val savedCenter = mapView.center?.let { PointF(it.x, it.y) }
        val savedScale = mapView.scale

        Logger.d(TAG, "Saving before padding: center=$savedCenter, scale=$savedScale")

        // Sauvegarder les layoutParams originaux
        if (originalLayoutParams == null) {
            originalLayoutParams = mapView.layoutParams
            Logger.d(TAG, "Original layout params saved: ${viewWidth}×${viewHeight}")
        }

        // Calculer la diagonale de l'écran
        val screenDiagonal = kotlin.math.hypot(viewWidth.toFloat(), viewHeight.toFloat())

        // Calculer le padding nécessaire
        val minDimension = minOf(viewWidth, viewHeight)
        val paddingNeeded = ((screenDiagonal - minDimension) / 2f * 1.1f).toInt()

        Logger.d(TAG, "Screen: ${viewWidth}×${viewHeight}, diagonal=$screenDiagonal, padding=$paddingNeeded")

        // Agrandir la vue physiquement
        val params = FrameLayout.LayoutParams(
            viewWidth + paddingNeeded * 2,
            viewHeight + paddingNeeded * 2
        )
        mapView.layoutParams = params
        mapView.requestLayout()

        // Déplacer la vue pour compenser
        mapView.translationX = -paddingNeeded.toFloat()
        mapView.translationY = -paddingNeeded.toFloat()

        // Ajuster le pivot de rotation + RESTAURER
        mapView.post {
            val pivotX = (viewWidth + paddingNeeded * 2) / 2f
            val pivotY = (viewHeight + paddingNeeded * 2) / 2f
            mapView.pivotX = pivotX
            mapView.pivotY = pivotY
            Logger.d(TAG, "Pivot set to ($pivotX, $pivotY)")

            // Restaure la position
            if (savedCenter != null && mapView.isReady) {
                mapView.setScaleAndCenter(savedScale, savedCenter)
                Logger.i(TAG, "Position restored after padding: center=$savedCenter, scale=$savedScale")
            }
        }

        rotationPaddingApplied = true

        Logger.i(TAG, "Rotation padding applied: newSize=${viewWidth + paddingNeeded * 2}×${viewHeight + paddingNeeded * 2}")
        Logger.exit(TAG, "applyRotationPadding")
    }

    /**
     * Supprime le padding virtuel de rotation
     */
    private fun removeRotationPadding() {
        Logger.entry(TAG, "removeRotationPadding")

        if (!rotationPaddingApplied) {
            Logger.d(TAG, "No rotation padding to remove")
            Logger.exit(TAG, "removeRotationPadding")
            return
        }

        // SAUVEGARDER la position actuelle AVANT les changements
        val savedCenter = if (mapView.isReady) {
            mapView.center?.let { PointF(it.x, it.y) }
        } else null

        val savedScale = if (mapView.isReady) mapView.scale else null

        Logger.d(TAG, "Saving: center=$savedCenter, scale=$savedScale")

        // Restaurer les layoutParams originaux
        if (originalLayoutParams != null) {
            mapView.layoutParams = originalLayoutParams
            mapView.requestLayout()
            Logger.d(TAG, "Original layout params restored")
        }

        // Réinitialiser la translation
        mapView.translationX = 0f
        mapView.translationY = 0f

        // Réinitialiser le pivot
        mapView.post {
            val pivotX = mapView.width / 2f
            val pivotY = mapView.height / 2f
            mapView.pivotX = pivotX
            mapView.pivotY = pivotY
            Logger.d(TAG, "Pivot reset to ($pivotX, $pivotY)")

            // RESTAURER exactement le même point au même zoom
            if (savedCenter != null && savedScale != null && mapView.isReady) {
                mapView.setScaleAndCenter(savedScale, savedCenter)
                Logger.i(TAG, "Position restored: center=$savedCenter, scale=$savedScale")
            }
        }

        rotationPaddingApplied = false

        Logger.i(TAG, "Rotation padding removed")
        Logger.exit(TAG, "removeRotationPadding")
    }

    /**
     * Active ou désactive la rotation
     */
    fun setRotationEnabled(enabled: Boolean) {
        Logger.entry(TAG, "setRotationEnabled", enabled)

        if (isRotationEnabled != enabled) {
            isRotationEnabled = enabled

            if (!enabled) {
                // Remettre à 0° et enlever le padding
                setRotation(0f, false)
            }

            Logger.i(TAG, "Rotation ${if (enabled) "enabled" else "disabled"}")
        }

        Logger.exit(TAG, "setRotationEnabled")
    }

    /**
     * Vérifie si la rotation est active
     */
    fun isRotationEnabled(): Boolean = isRotationEnabled

    /**
     * Récupère la rotation actuelle
     */
    fun getRotation(): Float = mapView.rotation

    /**
     * Récupère l'échelle actuelle
     */
    fun getScale(): Float = if (mapView.isReady) mapView.scale else 0f

    /**
     * Récupère le centre actuel
     */
    fun getCenter(): PointF? = if (mapView.isReady) mapView.center else null

    /**
     * Vérifie si la carte est prête
     */
    fun isReady(): Boolean = mapView.isReady

    /**
     * Récupère les dimensions de la carte
     */
    fun getMapDimensions(): Pair<Int, Int> {
        return if (mapView.isReady) {
            Pair(mapView.sWidth, mapView.sHeight)
        } else {
            Logger.w(TAG, "Map not ready, returning 0x0 dimensions")
            Pair(0, 0)
        }
    }

    /**
     * Récupère la carte actuelle
     */
    fun getCurrentMap(): MapItem? = currentMap

    /**
     * Vérifie si le mode sombre est actif
     */
    fun isDarkModeEnabled(): Boolean = isDarkMode

    /**
     * Reset la vue
     */
    fun reset() {
        Logger.entry(TAG, "reset")
        mapState.reset()
        isMapAdjusted = false
        adjustRetryCount = 0
        isModeSwitching = false
        if (rotationPaddingApplied) {
            removeRotationPadding()
        }
        originalLayoutParams = null
        Logger.i(TAG, "MapViewController reset")
        Logger.exit(TAG, "reset")
    }


    /**
     * Cleanup
     */
    fun cleanup() {
        Logger.entry(TAG, "cleanup")
        try {
            if (rotationPaddingApplied) {
                removeRotationPadding()
            }
            mapView.setOnImageEventListener(null)
            mapView.recycle()
            mapExecutor?.shutdown()
            if (mapExecutor?.awaitTermination(1, TimeUnit.SECONDS) == false) {
                mapExecutor?.shutdownNow()
            }
            mapExecutor = null
            Logger.i(TAG, "MapViewController cleaned up")
        } catch (e: Exception) {
            Logger.e(TAG, "Error during cleanup", e)
        }
        Logger.exit(TAG, "cleanup")
    }

    /**
     * Log l'état actuel
     */
    fun logState() {
        Logger.state(TAG, "MapViewController", mapOf(
            "currentMap" to (currentMap?.name ?: "none"),
            "isDarkMode" to isDarkMode,
            "isMapAdjusted" to isMapAdjusted,
            "isModeSwitching" to isModeSwitching,
            "isReady" to mapView.isReady,
            "rotation" to mapView.rotation,
            "scale" to if (mapView.isReady) mapView.scale else "N/A",
            "dimensions" to if (mapView.isReady) "${mapView.sWidth}x${mapView.sHeight}" else "N/A",
            "executorActive" to (mapExecutor != null && !mapExecutor!!.isShutdown),
            "isRotationEnabled" to isRotationEnabled,
            "currentRotation" to currentRotation,
            "rotationPaddingApplied" to rotationPaddingApplied
        ))
    }
}

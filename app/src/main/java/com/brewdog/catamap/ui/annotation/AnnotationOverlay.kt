package com.brewdog.catamap.ui.annotation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.brewdog.catamap.domain.annotation.LayerChangeListener
import com.brewdog.catamap.domain.annotation.LayerManager
import com.brewdog.catamap.domain.annotation.models.AnnotationEdit
import com.brewdog.catamap.domain.annotation.models.Layer
import com.brewdog.catamap.domain.annotation.tools.ToolType
import com.brewdog.catamap.domain.annotation.tools.ToolsManager
import com.brewdog.catamap.domain.annotation.tools.ToolsStateListener
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.sqrt

/**
 * Overlay transparent pour afficher et gérer les annotations
 *
 * VERSION 2.3 - DRAG & DROP :
 * - Tap simple (< 10px) → Édition
 * - Drag (> 10px) → Déplacement
 * - Feedback visuel (semi-transparent pendant drag)
 */
class AnnotationOverlay : Fragment(), ToolsStateListener, LayerChangeListener {

    companion object {
        private const val TAG = "AnnotationOverlay"
        private const val ARG_MAP_ID = "map_id"
        private const val ARG_IS_DARK_MODE = "is_dark_mode"

        fun newInstance(
            mapId: String,
            isDarkMode: Boolean,
            toolsManager: ToolsManager,
            layerManager: LayerManager,
            mapView: SubsamplingScaleImageView
        ): AnnotationOverlay {
            return AnnotationOverlay().apply {
                arguments = Bundle().apply {
                    putString(ARG_MAP_ID, mapId)
                    putBoolean(ARG_IS_DARK_MODE, isDarkMode)
                }
                this.toolsManager = toolsManager
                this.layerManager = layerManager
                this.mapView = mapView
            }
        }
    }

    private lateinit var toolsManager: ToolsManager
    private lateinit var layerManager: LayerManager
    private lateinit var mapView: SubsamplingScaleImageView

    private var mapId: String = ""
    private var isDarkMode: Boolean = false

    private lateinit var canvasView: AnnotationCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.entry(TAG, "onCreate")

        mapId = arguments?.getString(ARG_MAP_ID) ?: ""
        isDarkMode = arguments?.getBoolean(ARG_IS_DARK_MODE) ?: false

        Logger.d(TAG, "Initialized: mapId=$mapId, isDarkMode=$isDarkMode")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Logger.entry(TAG, "onCreateView")

        canvasView = AnnotationCanvasView(requireContext()).apply {
            isClickable = true
            isFocusable = true

            this.toolsManager = this@AnnotationOverlay.toolsManager
            this.layerManager = this@AnnotationOverlay.layerManager
            this.mapView = this@AnnotationOverlay.mapView
            this.isDarkMode = this@AnnotationOverlay.isDarkMode
        }

        return canvasView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.entry(TAG, "onViewCreated")

        toolsManager.addListener(this)
        layerManager.addListener(this)

        canvasView.invalidate()

        Logger.i(TAG, "AnnotationOverlay ready (permanent display)")
    }

    override fun onToolChanged(previous: ToolType, current: ToolType) {
        Logger.d(TAG, "Tool changed: ${previous.name} → ${current.name}")
        canvasView.onToolChanged(current)
    }

    override fun onColorChanged(color: Int) {
        Logger.d(TAG, "Color changed: ${String.format("#%08X", color)}")
        canvasView.invalidate()
    }

    override fun onTextSizeChanged(size: Int) {
        Logger.d(TAG, "Text size changed: ${size}sp")
        canvasView.invalidate()
    }

    override fun onLayersChanged(layers: List<Layer>, activeLayerId: String) {
        Logger.d(TAG, "Layers changed: ${layers.size} layers, active=$activeLayerId")

        val visibleCount = layers.count { it.isVisible }
        Logger.d(TAG, "Visible layers: $visibleCount")

        canvasView.invalidate()
    }

    fun updateDarkMode(newDarkMode: Boolean) {
        Logger.entry(TAG, "updateDarkMode", newDarkMode)

        if (isDarkMode != newDarkMode) {
            isDarkMode = newDarkMode
            canvasView.isDarkMode = newDarkMode
            canvasView.invalidate()

            Logger.i(TAG, "Dark mode updated: $newDarkMode, annotations redrawn")
        }
    }

    fun refresh() {
        canvasView.invalidate()
    }

    override fun onDestroyView() {
        toolsManager.removeListener(this)
        layerManager.removeListener(this)
        super.onDestroyView()
        Logger.d(TAG, "AnnotationOverlay destroyed")
    }
}

/**
 * Vue Canvas personnalisée pour le rendu des annotations
 */
class AnnotationCanvasView(context: Context) : View(context) {

    companion object {
        private const val TAG = "AnnotationCanvasView"
        private const val DRAG_THRESHOLD_DP = 10f
    }

    lateinit var toolsManager: ToolsManager
    lateinit var layerManager: LayerManager
    lateinit var mapView: SubsamplingScaleImageView
    var isDarkMode: Boolean = false

    private val renderer = AnnotationRenderer()
    private var currentTool: ToolType = ToolType.NONE

    // Paint pour calcul de bounds
    private val measurePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // État du drag
    private enum class TouchMode {
        NONE,      // Pas de touch actif
        WAITING,   // Touch down, en attente
        DRAGGING,  // Drag en cours
        EDITING    // Édition (tap simple)
    }

    private var touchMode = TouchMode.NONE
    private var draggedTextAndLayer: Pair<AnnotationEdit.Text, Layer>? = null
    private var dragStartPoint: PointF? = null
    private var dragCurrentPoint: PointF? = null
    private val dragThreshold: Float
        get() = DRAG_THRESHOLD_DP * resources.displayMetrics.density

    init {
        Logger.d(TAG, "AnnotationCanvasView created")
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!::mapView.isInitialized || !mapView.isReady) {
            Logger.v(TAG, "Map not ready, skipping draw")
            return
        }

        if (!::layerManager.isInitialized) {
            Logger.v(TAG, "LayerManager not ready, skipping draw")
            return
        }

        val allLayers = layerManager.getLayers()
        val visibleLayers = allLayers.filter { it.isVisible }

        if (visibleLayers.isEmpty()) {
            Logger.v(TAG, "No visible layers")
            return
        }

        val layers = visibleLayers.sortedBy { it.zIndex }

        canvas.save()

        try {
            applyMapTransformations(canvas)

            layers.forEach { layer ->
                layer.annotations.forEach { annotation ->
                    when (annotation) {
                        is AnnotationEdit.Text -> {
                            drawTextAnnotation(canvas, annotation, layer)
                        }
                        is AnnotationEdit.Drawing -> {
                            renderer.drawPath(canvas, annotation, isDarkMode)
                        }
                    }
                }
            }

            val totalAnnotations = layers.sumOf { it.annotations.size }
            if (totalAnnotations > 0) {
                Logger.v(TAG, "Drew $totalAnnotations annotations from ${layers.size} visible layers")
            }

        } finally {
            canvas.restore()
        }
    }

    /**
     * Dessine une annotation texte avec gestion du drag
     */
    private fun drawTextAnnotation(canvas: Canvas, annotation: AnnotationEdit.Text, layer: Layer) {
        // Vérifier si c'est le texte en cours de drag
        val isDragging = touchMode == TouchMode.DRAGGING &&
                draggedTextAndLayer?.first?.id == annotation.id

        if (isDragging) {
            // Dessiner à la position du doigt, semi-transparent
            val position = dragCurrentPoint ?: annotation.position
            renderer.drawText(canvas, annotation, isDarkMode, position, alpha = 128)
        } else {
            // Dessiner normalement
            renderer.drawText(canvas, annotation, isDarkMode)
        }
    }

    private fun applyMapTransformations(canvas: Canvas) {
        val scale = mapView.scale
        val center = mapView.center ?: return

        val viewWidth = mapView.width.toFloat()
        val viewHeight = mapView.height.toFloat()

        val offsetX = viewWidth / 2f - center.x * scale
        val offsetY = viewHeight / 2f - center.y * scale

        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentTool == ToolType.NONE) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleTouchDown(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouchMove(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleTouchUp(event)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleTouchDown(event: MotionEvent) {
        val screenPoint = PointF(event.x, event.y)
        val imagePoint = screenToImageCoordinates(screenPoint)

        Logger.d(TAG, "Touch down: screen=$screenPoint, image=$imagePoint, tool=$currentTool")

        when (currentTool) {
            ToolType.TEXT -> {
                handleTextToolDown(imagePoint)
            }
            ToolType.DRAWING -> {
                // TODO: Phase 2
            }
            ToolType.ERASER -> {
                // TODO: Phase 3
            }
            else -> {}
        }
    }

    private fun handleTouchMove(event: MotionEvent) {
        val screenPoint = PointF(event.x, event.y)
        val imagePoint = screenToImageCoordinates(screenPoint)

        when (currentTool) {
            ToolType.TEXT -> {
                handleTextToolMove(imagePoint)
            }
            ToolType.DRAWING -> {
                // TODO: Phase 2
            }
            else -> {}
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
        val screenPoint = PointF(event.x, event.y)
        val imagePoint = screenToImageCoordinates(screenPoint)

        Logger.d(TAG, "Touch up: screen=$screenPoint, image=$imagePoint, mode=$touchMode")

        when (currentTool) {
            ToolType.TEXT -> {
                handleTextToolUp(imagePoint)
            }
            ToolType.DRAWING -> {
                // TODO: Phase 2
            }
            else -> {}
        }

        // Reset état
        touchMode = TouchMode.NONE
        draggedTextAndLayer = null
        dragStartPoint = null
        dragCurrentPoint = null
    }

    private fun screenToImageCoordinates(screenPoint: PointF): PointF {
        val scale = mapView.scale
        val center = mapView.center ?: return PointF(0f, 0f)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val offsetX = viewWidth / 2f - center.x * scale
        val offsetY = viewHeight / 2f - center.y * scale

        val imageX = (screenPoint.x - offsetX) / scale
        val imageY = (screenPoint.y - offsetY) / scale

        return PointF(imageX, imageY)
    }

    /**
     * Gère le touch down pour l'outil texte
     */
    private fun handleTextToolDown(imagePoint: PointF) {
        // Vérifier si on a tapé sur un texte existant
        val tappedText = findTextAnnotationAtPoint(imagePoint)

        if (tappedText != null) {
            val (text, layer) = tappedText
            val activeLayerId = toolsManager.activeLayerId

            if (layer.id == activeLayerId) {
                // Texte sur calque actif → Préparer pour drag ou édition
                touchMode = TouchMode.WAITING
                draggedTextAndLayer = tappedText
                dragStartPoint = imagePoint

                Logger.d(TAG, "Touch down on text: \"${text.content}\", waiting for move or up")
            } else {
                // Texte sur autre calque → Toast
                Toast.makeText(
                    context,
                    "Ce texte est sur un autre calque (${layer.name})",
                    Toast.LENGTH_SHORT
                ).show()

                touchMode = TouchMode.NONE
                Logger.d(TAG, "Text on inactive layer: ${layer.name}")
            }
        } else {
            // Pas de texte trouvé → Création
            touchMode = TouchMode.NONE
        }
    }

    /**
     * Gère le move pour l'outil texte
     */
    private fun handleTextToolMove(imagePoint: PointF) {
        if (touchMode == TouchMode.WAITING) {
            // Vérifier si on a dépassé le seuil de drag
            val distance = calculateDistance(dragStartPoint!!, imagePoint)

            if (distance > dragThreshold) {
                // Passer en mode drag
                touchMode = TouchMode.DRAGGING
                dragCurrentPoint = imagePoint

                val text = draggedTextAndLayer?.first
                Logger.i(TAG, "Drag started for text: \"${text?.content}\", distance=$distance")

                invalidate()
            }
        } else if (touchMode == TouchMode.DRAGGING) {
            // Mettre à jour la position du drag
            dragCurrentPoint = imagePoint
            invalidate()
        }
    }

    /**
     * Gère le touch up pour l'outil texte
     */
    private fun handleTextToolUp(imagePoint: PointF) {
        when (touchMode) {
            TouchMode.WAITING -> {
                // Tap simple → Édition
                val (text, layer) = draggedTextAndLayer!!
                Logger.i(TAG, "Tap detected, opening edit dialog for: \"${text.content}\"")
                showTextEditDialog(text.position, text)
            }

            TouchMode.DRAGGING -> {
                // Fin du drag → Sauvegarder nouvelle position
                saveDraggedTextPosition(imagePoint)
            }

            TouchMode.NONE -> {
                // Création d'un nouveau texte
                Logger.d(TAG, "Creating new text at $imagePoint")
                showTextEditDialog(imagePoint, null)
            }

            else -> {}
        }
    }

    /**
     * Sauvegarde la nouvelle position du texte après drag
     */
    private fun saveDraggedTextPosition(newPosition: PointF) {
        val (text, layer) = draggedTextAndLayer ?: return

        // Créer annotation mise à jour
        val updatedText = text.copy(position = newPosition)

        // Remplacer dans le calque
        layer.removeAnnotation(text.id)
        layer.addAnnotation(updatedText)

        // Sauvegarder
        layerManager.saveAnnotations()

        Logger.i(TAG, "Text moved: \"${text.content}\" from ${text.position} to $newPosition")

        invalidate()
    }

    /**
     * Calcule la distance entre deux points
     */
    private fun calculateDistance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Trouve une annotation texte au point donné
     */
    private fun findTextAnnotationAtPoint(point: PointF): Pair<AnnotationEdit.Text, Layer>? {
        val allLayers = layerManager.getLayers()
        val visibleLayers = allLayers.filter { it.isVisible }.sortedBy { it.zIndex }

        // Parcourir du plus haut au plus bas
        for (layer in visibleLayers.reversed()) {
            for (annotation in layer.annotations.reversed()) {
                if (annotation is AnnotationEdit.Text) {
                    if (isPointInTextBounds(point, annotation)) {
                        Logger.d(TAG, "Found text at tap: \"${annotation.content}\" on layer ${layer.name}")
                        return Pair(annotation, layer)
                    }
                }
            }
        }

        Logger.v(TAG, "No text found at tap point")
        return null
    }

    /**
     * Vérifie si un point est dans les bounds d'un texte
     */
    private fun isPointInTextBounds(point: PointF, annotation: AnnotationEdit.Text): Boolean {
        measurePaint.textSize = annotation.fontSize

        val textWidth = measurePaint.measureText(annotation.content)

        val bounds = Rect()
        measurePaint.getTextBounds(annotation.content, 0, annotation.content.length, bounds)
        val textHeight = bounds.height().toFloat()

        val left = annotation.position.x - textWidth / 2
        val right = annotation.position.x + textWidth / 2
        val top = annotation.position.y + measurePaint.ascent()
        val bottom = annotation.position.y + measurePaint.descent()

        val isInside = point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

        if (isInside) {
            Logger.v(TAG, "Point in bounds: text=\"${annotation.content}\"")
        }

        return isInside
    }

    private fun showTextEditDialog(position: PointF, existingText: AnnotationEdit.Text?) {
        val fragmentManager = (context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
        if (fragmentManager == null) {
            Logger.e(TAG, "Cannot get FragmentManager")
            return
        }

        val dialog = TextEditDialog.newInstance(
            initialText = existingText?.content ?: "",
            onConfirm = { text ->
                handleTextConfirmed(text, position, existingText)
            }
        )

        dialog.show(fragmentManager, "TextEditDialog")
    }

    private fun handleTextConfirmed(
        text: String,
        position: PointF,
        existingText: AnnotationEdit.Text?
    ) {
        val activeLayerId = toolsManager.activeLayerId
        if (activeLayerId == null) {
            Logger.e(TAG, "No active layer")
            return
        }

        val activeLayer = layerManager.getLayers().find { it.id == activeLayerId }
        if (activeLayer == null) {
            Logger.e(TAG, "Active layer not found: $activeLayerId")
            return
        }

        if (existingText != null) {
            // Édition
            if (text.isBlank()) {
                // Supprimer
                activeLayer.removeAnnotation(existingText.id)
                Logger.i(TAG, "Text annotation deleted: \"${existingText.content}\"")
            } else {
                // Mettre à jour
                val updatedText = existingText.copy(content = text)
                activeLayer.removeAnnotation(existingText.id)
                activeLayer.addAnnotation(updatedText)
                Logger.i(TAG, "Text annotation updated: \"${existingText.content}\" → \"$text\"")
            }

            layerManager.saveAnnotations()
            invalidate()

        } else {
            // Création
            if (text.isBlank()) {
                Logger.d(TAG, "Empty text, ignoring")
                return
            }

            val annotation = AnnotationEdit.Text(
                content = text,
                position = position,
                fontSize = toolsManager.textSize.toFloat(),
                color = com.brewdog.catamap.domain.annotation.models.AnnotationColor.fromBaseColor(
                    toolsManager.activeColor,
                    isDarkMode
                )
            )

            activeLayer.addAnnotation(annotation)
            Logger.i(TAG, "Text annotation created: \"$text\" at $position")

            layerManager.saveAnnotations()
            invalidate()
        }
    }

    fun onToolChanged(tool: ToolType) {
        currentTool = tool
        Logger.d(TAG, "Current tool: $tool")
    }
}
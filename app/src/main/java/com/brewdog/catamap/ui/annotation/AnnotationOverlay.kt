package com.brewdog.catamap.ui.annotation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.brewdog.catamap.domain.annotation.tools.DouglasPeucker
import com.brewdog.catamap.domain.annotation.tools.ToolType
import com.brewdog.catamap.domain.annotation.tools.ToolsManager
import com.brewdog.catamap.domain.annotation.tools.ToolsStateListener
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.UUID
import kotlin.math.sqrt

/**
 * Overlay transparent pour afficher et gérer les annotations
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
            this.fragmentManager = this@AnnotationOverlay.childFragmentManager
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

    override fun onStrokeWidthChanged(width: Float) {
        Logger.d(TAG, "Stroke width changed: ${width}px")
        // Redessiner si dessin en cours
        canvasView.onStrokeWidthChanged()
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
        private const val SMOOTHING_EPSILON = 2.0f  // Douglas-Peucker tolerance
    }

    lateinit var toolsManager: ToolsManager
    lateinit var layerManager: LayerManager
    lateinit var mapView: SubsamplingScaleImageView
    var isDarkMode: Boolean = false

    private val renderer = AnnotationRenderer()
    private var currentTool: ToolType = ToolType.NONE

    // Paint pour calcul de bounds (texte)
    private val measurePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // État du drag (texte)
    private enum class TouchMode {
        NONE,
        WAITING,
        DRAGGING,
        EDITING
    }

    private var touchMode = TouchMode.NONE
    private var draggedTextAndLayer: Pair<AnnotationEdit.Text, Layer>? = null
    private var dragStartPoint: PointF? = null
    private var dragCurrentPoint: PointF? = null
    private val dragThreshold: Float
        get() = DRAG_THRESHOLD_DP * resources.displayMetrics.density

    // État du dessin
    private var currentDrawingPoints = mutableListOf<PointF>()
    private var isDrawing = false
    private var isErasing = false
    private var eraserPosition: PointF? = null
    private var eraserRadius: Float
        get() = toolsManager.eraserSize
        set(value) {
            toolsManager.eraserSize = value
        }

    // Pour le découpage des dessins
    private data class PointToRemove(
        val drawingId: String,
        val pointIndex: Int
    )
    private val pointsToRemove = mutableSetOf<PointToRemove>()

    // FragmentManager pour les dialogs
    lateinit var fragmentManager: androidx.fragment.app.FragmentManager

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

        if (visibleLayers.isEmpty() && !isDrawing) {
            Logger.v(TAG, "No visible layers and not drawing")
            return
        }

        val layers = visibleLayers.sortedBy { it.zIndex }

        canvas.save()

        try {
            applyMapTransformations(canvas)

            // Dessiner annotations existantes
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

            // Dessiner le trait en cours (temporaire)
            if (isDrawing && currentDrawingPoints.size > 1) {
                drawTemporaryDrawing(canvas, currentDrawingPoints)
            }
            if (isErasing && eraserPosition != null) {
                drawEraserCursor(canvas, eraserPosition!!)
            }

            val totalAnnotations = layers.sumOf { it.annotations.size }
            if (totalAnnotations > 0 || isDrawing) {
                Logger.v(TAG, "Drew $totalAnnotations annotations from ${layers.size} visible layers (drawing=$isDrawing)")
            }

        } finally {
            canvas.restore()
        }
    }

    /**
     * Dessine une annotation texte avec gestion du drag
     */
    private fun drawTextAnnotation(canvas: Canvas, annotation: AnnotationEdit.Text, layer: Layer) {
        val isDragging = touchMode == TouchMode.DRAGGING &&
                draggedTextAndLayer?.first?.id == annotation.id

        if (isDragging) {
            val position = dragCurrentPoint ?: annotation.position
            renderer.drawText(canvas, annotation, isDarkMode, position, alpha = 128)
        } else {
            renderer.drawText(canvas, annotation, isDarkMode)
        }
    }

    /**
     * Dessine le trait en cours de dessin (avant lissage)
     */
    private fun drawTemporaryDrawing(canvas: Canvas, points: List<PointF>) {
        val paint = Paint().apply {
            color = toolsManager.activeColor
            strokeWidth = toolsManager.strokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            alpha = 255  // Opaque
        }

        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }

        canvas.drawPath(path, paint)

        Logger.v(TAG, "Drew temporary drawing: ${points.size} points")
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
                handleDrawingToolDown(imagePoint)
            }
            ToolType.ERASER -> {
                handleEraserToolDown(imagePoint)
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
                handleDrawingToolMove(imagePoint)
            }
            ToolType.ERASER -> {
                handleEraserToolMove(imagePoint)
            }
            else -> {}
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
        val screenPoint = PointF(event.x, event.y)
        val imagePoint = screenToImageCoordinates(screenPoint)

        Logger.d(TAG, "Touch up: screen=$screenPoint, image=$imagePoint, mode=$touchMode, drawing=$isDrawing")

        when (currentTool) {
            ToolType.TEXT -> {
                handleTextToolUp(imagePoint)
            }
            ToolType.DRAWING -> {
                handleDrawingToolUp()
            }
            ToolType.ERASER -> {
                handleEraserToolUp()
            }
            else -> {}
        }

        // Reset état texte
        touchMode = TouchMode.NONE
        draggedTextAndLayer = null
        dragStartPoint = null
        dragCurrentPoint = null
    }

    // ========== OUTIL DESSIN ==========

    /**
     * Commence un nouveau dessin
     */
    private fun handleDrawingToolDown(point: PointF) {
        currentDrawingPoints.clear()
        currentDrawingPoints.add(point)
        isDrawing = true

        Logger.d(TAG, "Drawing started at $point")
    }

    /**
     * Continue le dessin (ajoute des points)
     */
    private fun handleDrawingToolMove(point: PointF) {
        if (isDrawing) {
            currentDrawingPoints.add(point)
            invalidate()  // Redessiner en temps réel
        }
    }

    /**
     * Termine le dessin (lissage + sauvegarde)
     */
    private fun handleDrawingToolUp() {
        if (!isDrawing) return

        Logger.d(TAG, "Drawing ended: ${currentDrawingPoints.size} points")

        if (currentDrawingPoints.size >= 2) {
            // Appliquer lissage Douglas-Peucker
            val smoothedPoints = DouglasPeucker.simplify(currentDrawingPoints, SMOOTHING_EPSILON)

            val reduction = DouglasPeucker.calculateReduction(
                currentDrawingPoints.size,
                smoothedPoints.size
            )

            Logger.i(TAG, "Drawing smoothed: ${currentDrawingPoints.size} → ${smoothedPoints.size} points (${reduction.toInt()}% reduction)")

            // Créer annotation
            val drawing = AnnotationEdit.Drawing(
                points = smoothedPoints,
                strokeWidth = toolsManager.strokeWidth,
                color = com.brewdog.catamap.domain.annotation.models.AnnotationColor.fromBaseColor(
                    toolsManager.activeColor,
                    isDarkMode
                )
            )

            // Ajouter au calque actif
            val activeLayerId = toolsManager.activeLayerId
            if (activeLayerId == null) {
                Logger.e(TAG, "No active layer")
            } else {
                val activeLayer = layerManager.getLayers().find { it.id == activeLayerId }
                if (activeLayer == null) {
                    Logger.e(TAG, "Active layer not found: $activeLayerId")
                } else {
                    activeLayer.addAnnotation(drawing)
                    Logger.i(TAG, "Drawing annotation created: ${smoothedPoints.size} points on layer ${activeLayer.name}")

                    // Sauvegarder
                    layerManager.saveAnnotations()
                }
            }
        } else {
            Logger.d(TAG, "Drawing ignored: not enough points (${currentDrawingPoints.size})")
        }

        // Reset état dessin
        isDrawing = false
        currentDrawingPoints.clear()
        invalidate()
    }

    // ========== OUTIL TEXTE ==========

    private fun handleTextToolDown(imagePoint: PointF) {
        val tappedText = findTextAnnotationAtPoint(imagePoint)

        if (tappedText != null) {
            val (text, layer) = tappedText
            val activeLayerId = toolsManager.activeLayerId

            if (layer.id == activeLayerId) {
                touchMode = TouchMode.WAITING
                draggedTextAndLayer = tappedText
                dragStartPoint = imagePoint

                Logger.d(TAG, "Touch down on text: \"${text.content}\", waiting for move or up")
            } else {
                Toast.makeText(
                    context,
                    "Ce texte est sur un autre calque (${layer.name})",
                    Toast.LENGTH_SHORT
                ).show()

                touchMode = TouchMode.NONE
                Logger.d(TAG, "Text on inactive layer: ${layer.name}")
            }
        } else {
            touchMode = TouchMode.NONE
        }
    }

    private fun handleTextToolMove(imagePoint: PointF) {
        if (touchMode == TouchMode.WAITING) {
            val distance = calculateDistance(dragStartPoint!!, imagePoint)

            if (distance > dragThreshold) {
                touchMode = TouchMode.DRAGGING
                dragCurrentPoint = imagePoint

                val text = draggedTextAndLayer?.first
                Logger.i(TAG, "Drag started for text: \"${text?.content}\", distance=$distance")

                invalidate()
            }
        } else if (touchMode == TouchMode.DRAGGING) {
            dragCurrentPoint = imagePoint
            invalidate()
        }
    }

    private fun handleTextToolUp(imagePoint: PointF) {
        when (touchMode) {
            TouchMode.WAITING -> {
                val (text, layer) = draggedTextAndLayer!!
                Logger.i(TAG, "Tap detected, opening edit dialog for: \"${text.content}\"")
                showTextEditDialog(text.position, text)
            }

            TouchMode.DRAGGING -> {
                saveDraggedTextPosition(imagePoint)
            }

            TouchMode.NONE -> {
                Logger.d(TAG, "Creating new text at $imagePoint")
                showTextEditDialog(imagePoint, null)
            }

            else -> {}
        }
    }

    private fun saveDraggedTextPosition(newPosition: PointF) {
        val (text, layer) = draggedTextAndLayer ?: return

        val updatedText = text.copy(position = newPosition)

        layer.removeAnnotation(text.id)
        layer.addAnnotation(updatedText)

        layerManager.saveAnnotations()

        Logger.i(TAG, "Text moved: \"${text.content}\" from ${text.position} to $newPosition")

        invalidate()
    }

    private fun calculateDistance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun findTextAnnotationAtPoint(point: PointF): Pair<AnnotationEdit.Text, Layer>? {
        val allLayers = layerManager.getLayers()
        val visibleLayers = allLayers.filter { it.isVisible }.sortedBy { it.zIndex }

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
            if (text.isBlank()) {
                activeLayer.removeAnnotation(existingText.id)
                Logger.i(TAG, "Text annotation deleted: \"${existingText.content}\"")
            } else {
                val updatedText = existingText.copy(content = text)
                activeLayer.removeAnnotation(existingText.id)
                activeLayer.addAnnotation(updatedText)
                Logger.i(TAG, "Text annotation updated: \"${existingText.content}\" → \"$text\"")
            }

            layerManager.saveAnnotations()
            invalidate()

        } else {
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

        // Si on change d'outil pendant un dessin, l'annuler
        if (isDrawing && tool != ToolType.DRAWING) {
            Logger.w(TAG, "Drawing cancelled: tool changed")
            isDrawing = false
            currentDrawingPoints.clear()
            invalidate()
        }
        // Annuler gomme en cours
        if (isErasing && tool != ToolType.ERASER) {
            Logger.w(TAG, "Erasing cancelled: tool changed")
            isErasing = false
            eraserPosition = null
            pointsToRemove.clear()
            invalidate()
        }
    }

    fun onStrokeWidthChanged() {
        // Redessiner si dessin en cours
        if (isDrawing) {
            invalidate()
        }
    }
    // ========== OUTIL GOMME ==========

    /**
     * Gère le down pour l'outil gomme
     */
    private fun handleEraserToolDown(point: PointF) {
        isErasing = true
        eraserPosition = point

        // Vérifier si on tape sur un texte
        val tappedText = findTextAnnotationAtPoint(point)
        if (tappedText != null) {
            val (text, layer) = tappedText
            val activeLayerId = toolsManager.activeLayerId

            if (layer.id == activeLayerId) {
                // Texte sur calque actif → Dialog de confirmation
                showEraseTextDialog(text, layer)
                Logger.d(TAG, "Eraser tapped on text: \"${text.content}\"")
            } else {
                Toast.makeText(
                    context,
                    "Ce texte est sur un autre calque (${layer.name})",
                    Toast.LENGTH_SHORT
                ).show()
            }

            isErasing = false
            eraserPosition = null
            return
        }

        // Marquer les points de dessin à supprimer
        checkAndMarkPointsForRemoval(point)
        invalidate()

        Logger.d(TAG, "Eraser started at $point")
    }

    /**
     * Gère le move pour l'outil gomme
     */
    private fun handleEraserToolMove(point: PointF) {
        if (isErasing) {
            eraserPosition = point
            checkAndMarkPointsForRemoval(point)
            invalidate()
        }
    }

    /**
     * Gère le up pour l'outil gomme
     */
    private fun handleEraserToolUp() {
        if (!isErasing) return

        // Appliquer la suppression des points marqués
        if (pointsToRemove.isNotEmpty()) {
            applyErasure()
            Logger.i(TAG, "Eraser applied: ${pointsToRemove.size} points removed")
        }

        isErasing = false
        eraserPosition = null
        pointsToRemove.clear()
        invalidate()
    }

    /**
     * Vérifie et marque les points de dessin à supprimer
     */
    private fun checkAndMarkPointsForRemoval(eraserCenter: PointF) {
        val activeLayerId = toolsManager.activeLayerId ?: return
        val activeLayer = layerManager.getLayers().find { it.id == activeLayerId } ?: return

        activeLayer.annotations.forEach { annotation ->
            if (annotation is AnnotationEdit.Drawing) {
                annotation.points.forEachIndexed { index, point ->
                    val distance = calculateDistance(eraserCenter, point)
                    if (distance <= eraserRadius) {
                        pointsToRemove.add(PointToRemove(annotation.id, index))
                    }
                }
            }
        }
    }

    /**
     * Applique la suppression des points marqués et découpe les dessins
     */
    private fun applyErasure() {
        val activeLayerId = toolsManager.activeLayerId ?: return
        val activeLayer = layerManager.getLayers().find { it.id == activeLayerId } ?: return

        // Grouper les points par dessin
        val pointsByDrawing = pointsToRemove.groupBy { it.drawingId }

        pointsByDrawing.forEach { (drawingId, removedPoints) ->
            val drawing = activeLayer.annotations
                .filterIsInstance<AnnotationEdit.Drawing>()
                .find { it.id == drawingId }

            if (drawing != null) {
                val removedIndices = removedPoints.map { it.pointIndex }.toSet()

                // Découper le dessin en segments
                val newDrawings = splitDrawing(drawing, removedIndices)

                // Supprimer l'ancien dessin
                activeLayer.removeAnnotation(drawingId)

                // Ajouter les nouveaux segments
                newDrawings.forEach { newDrawing ->
                    activeLayer.addAnnotation(newDrawing)
                }

                Logger.i(TAG, "Drawing split: 1 → ${newDrawings.size} segments")
            }
        }

        // Sauvegarder
        layerManager.saveAnnotations()
    }

    /**
     * Découpe un dessin en segments séparés
     */
    private fun splitDrawing(
        drawing: AnnotationEdit.Drawing,
        removedIndices: Set<Int>
    ): List<AnnotationEdit.Drawing> {
        val segments = mutableListOf<MutableList<PointF>>()
        var currentSegment = mutableListOf<PointF>()

        drawing.points.forEachIndexed { index, point ->
            if (!removedIndices.contains(index)) {
                // Point conservé
                currentSegment.add(point)
            } else {
                // Point supprimé, terminer le segment actuel
                if (currentSegment.size >= 2) {
                    segments.add(currentSegment)
                }
                currentSegment = mutableListOf()
            }
        }

        // Dernier segment
        if (currentSegment.size >= 2) {
            segments.add(currentSegment)
        }

        // Créer les nouveaux dessins
        return segments.map { pointsList ->
            drawing.copy(
                id = UUID.randomUUID().toString(),
                points = pointsList
            )
        }
    }

    /**
     * Affiche le dialog de confirmation pour supprimer un texte
     */
    private fun showEraseTextDialog(text: AnnotationEdit.Text, layer: Layer) {
        val dialog = EraseTextConfirmDialog.newInstance(
            textContent = text.content,
            onConfirm = {
                // Supprimer le texte
                layer.removeAnnotation(text.id)
                layerManager.saveAnnotations()
                invalidate()

                Logger.i(TAG, "Text erased: \"${text.content}\" from layer ${layer.name}")
            }
        )

        dialog.show(fragmentManager, "EraseTextConfirmDialog")
    }

    /**
     * Dessine le curseur de gomme (cercle outline rouge)
     */
    private fun drawEraserCursor(canvas: Canvas, position: PointF) {
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        canvas.drawCircle(position.x, position.y, eraserRadius, paint)
    }

    /**
     * Listener pour changement taille gomme
     */
    fun onEraserSizeChanged() {
        // Redessiner si gomme en cours
        if (isErasing) {
            invalidate()
        }
    }
}
package com.brewdog.catamap.domain.annotation.tools

import android.content.Context
import android.content.SharedPreferences
import com.brewdog.catamap.constants.AnnotationConstants
import com.brewdog.catamap.domain.annotation.tools.ToolType.Companion.getLabel
import com.brewdog.catamap.domain.annotation.tools.ToolType.Companion.locksNavigation
import com.brewdog.catamap.domain.annotation.tools.ToolType.Companion.showsColor
import com.brewdog.catamap.utils.logging.Logger

/**
 * Gestionnaire centralisé de l'état des outils d'annotation
 *
 * Responsabilités :
 * - Gestion de l'outil actif
 * - Persistance des paramètres (couleur, taille, épaisseur)
 * - Notifications des changements d'état
 */
class ToolsManager(context: Context) {

    companion object {
        private const val TAG = "ToolsManager"
        private const val PREFS_NAME = "annotation_tools"

        // Clés SharedPreferences
        private const val KEY_ACTIVE_COLOR = "active_color"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_STROKE_WIDTH = "stroke_width"
        private const val KEY_ERASER_SIZE = "eraser_size"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // État actuel
    private var _activeTool: ToolType = ToolType.NONE
    private var _activeColor: Int = loadColor()
    private var _textSize: Int = loadTextSize()
    private var _strokeWidth: Float = loadStrokeWidth()
    private var _eraserSize: Float = loadEraserSize()

    // Calque actif (pour utilisation pendant dessin/texte)
    var activeLayerId: String? = null
        private set
    var activeLayerName: String? = null
        private set

    // Listeners
    private val listeners = mutableListOf<ToolsStateListener>()

    // ========== Propriétés publiques ==========

    val activeTool: ToolType
        get() = _activeTool

    var activeColor: Int
        get() = _activeColor
        set(value) {
            if (_activeColor != value) {
                _activeColor = value
                saveColor(value)
                notifyColorChanged()
                Logger.i(TAG, "Color changed: ${String.format("#%08X", value)}")
            }
        }

    var textSize: Int
        get() = _textSize
        set(value) {
            val validSize = AnnotationConstants.Text.SIZES
                .minByOrNull { kotlin.math.abs(it - value) }
                ?: AnnotationConstants.Text.DEFAULT_SIZE

            if (_textSize != validSize) {
                _textSize = validSize
                saveTextSize(validSize)
                notifyTextSizeChanged()
                Logger.i(TAG, "Text size changed: ${validSize}sp")
            }
        }

    var strokeWidth: Float
        get() = _strokeWidth
        set(value) {
            val clamped = value.coerceIn(
                AnnotationConstants.Drawing.MIN_STROKE_WIDTH,
                AnnotationConstants.Drawing.MAX_STROKE_WIDTH
            )

            if (_strokeWidth != clamped) {
                _strokeWidth = clamped
                saveStrokeWidth(clamped)
                notifyStrokeWidthChanged()
                Logger.i(TAG, "Stroke width changed: ${clamped}px")
            }
        }

    var eraserSize: Float
        get() = _eraserSize
        set(value) {
            val clamped = value.coerceIn(
                AnnotationConstants.Eraser.MIN_ERASER_SIZE,
                AnnotationConstants.Eraser.MAX_ERASER_SIZE
            )

            if (_eraserSize != clamped) {
                _eraserSize = clamped
                saveEraserSize(clamped)
                notifyEraserSizeChanged()
                Logger.i(TAG, "Eraser size changed: ${clamped}px")
            }
        }

    // ========== Gestion de l'outil actif ==========

    /**
     * Définit l'outil actif
     * Toggle logic : si on clique sur l'outil déjà actif, on le désactive
     */
    fun setTool(tool: ToolType) {
        Logger.entry(TAG, "setTool", tool)

        val newTool = if (_activeTool == tool) ToolType.NONE else tool

        if (_activeTool != newTool) {
            val previousTool = _activeTool
            _activeTool = newTool

            Logger.i(TAG, "Tool changed: ${previousTool.getLabel()} → ${newTool.getLabel()}")
            notifyToolChanged(previousTool, newTool)
        }
    }

    /**
     * Désactive l'outil actuel
     */
    fun clearTool() {
        if (_activeTool != ToolType.NONE) {
            setTool(ToolType.NONE)
        }
    }

    /**
     * Vérifie si un outil spécifique est actif
     */
    fun isToolActive(tool: ToolType): Boolean {
        return _activeTool == tool
    }

    /**
     * Vérifie si la navigation est verrouillée
     */
    fun isNavigationLocked(): Boolean {
        return _activeTool.locksNavigation()
    }

    /**
     * Vérifie si la couleur doit être affichée
     */
    fun shouldShowColor(): Boolean {
        return _activeTool.showsColor()
    }

    // ========== Gestion du calque actif ==========

    /**
     * Définit le calque actif
     * Utilisé pour savoir sur quel calque dessiner/ajouter du texte
     */
    fun setActiveLayer(layerId: String, layerName: String) {
        if (activeLayerId != layerId) {
            activeLayerId = layerId
            activeLayerName = layerName
            Logger.i(TAG, "Active layer set: $layerName (id=$layerId)")
        }
    }

    /**
     * Efface les informations du calque actif
     */
    fun clearActiveLayer() {
        activeLayerId = null
        activeLayerName = null
        Logger.d(TAG, "Active layer cleared")
    }

    // ========== Persistence ==========

    private fun loadColor(): Int {
        return prefs.getInt(KEY_ACTIVE_COLOR, AnnotationConstants.Colors.DEFAULT_COLOR)
    }

    private fun saveColor(color: Int) {
        prefs.edit().putInt(KEY_ACTIVE_COLOR, color).apply()
    }

    private fun loadTextSize(): Int {
        return prefs.getInt(KEY_TEXT_SIZE, AnnotationConstants.Text.DEFAULT_SIZE)
    }

    private fun saveTextSize(size: Int) {
        prefs.edit().putInt(KEY_TEXT_SIZE, size).apply()
    }

    private fun loadStrokeWidth(): Float {
        return prefs.getFloat(KEY_STROKE_WIDTH, AnnotationConstants.Drawing.DEFAULT_STROKE_WIDTH)
    }

    private fun saveStrokeWidth(width: Float) {
        prefs.edit().putFloat(KEY_STROKE_WIDTH, width).apply()
    }
    private fun loadEraserSize(): Float {
        return prefs.getFloat(KEY_ERASER_SIZE, AnnotationConstants.Eraser.DEFAULT_ERASER_SIZE)
    }
    private fun saveEraserSize(size: Float) {
        prefs.edit().putFloat(KEY_ERASER_SIZE, size).apply()
    }

    // ========== Listeners ==========

    fun addListener(listener: ToolsStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            Logger.d(TAG, "Listener added: ${listener::class.simpleName}")
        }
    }

    fun removeListener(listener: ToolsStateListener) {
        if (listeners.remove(listener)) {
            Logger.d(TAG, "Listener removed: ${listener::class.simpleName}")
        }
    }

    private fun notifyToolChanged(previous: ToolType, current: ToolType) {
        listeners.forEach { it.onToolChanged(previous, current) }
    }

    private fun notifyColorChanged() {
        listeners.forEach { it.onColorChanged(_activeColor) }
    }

    private fun notifyTextSizeChanged() {
        listeners.forEach { it.onTextSizeChanged(_textSize) }
    }

    private fun notifyStrokeWidthChanged() {
        listeners.forEach { it.onStrokeWidthChanged(_strokeWidth) }
    }

    private fun notifyEraserSizeChanged() {
        listeners.forEach { it.onEraserSizeChanged(_eraserSize) }
    }

    // ========== Debug ==========

    fun logState() {
        Logger.state(TAG, "ToolsManager", mapOf(
            "activeTool" to _activeTool.getLabel(),
            "activeColor" to String.format("#%08X", _activeColor),
            "textSize" to "${_textSize}sp",
            "strokeWidth" to "${_strokeWidth}px",
            "eraserSize" to "${_eraserSize}px",
            "activeLayerId" to (activeLayerId ?: "none"),
            "activeLayerName" to (activeLayerName ?: "none"),
            "navigationLocked" to isNavigationLocked(),
            "showColor" to shouldShowColor(),
            "listenersCount" to listeners.size
        ))
    }
}

/**
 * Interface pour écouter les changements d'état des outils
 */
interface ToolsStateListener {
    fun onToolChanged(previous: ToolType, current: ToolType) {}
    fun onColorChanged(color: Int) {}
    fun onTextSizeChanged(size: Int) {}
    fun onStrokeWidthChanged(width: Float) {}
    fun onEraserSizeChanged(size: Float) {}
}
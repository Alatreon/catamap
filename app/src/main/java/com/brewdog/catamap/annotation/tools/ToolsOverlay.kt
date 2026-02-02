package com.brewdog.catamap.ui.annotation.tools

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.brewdog.catamap.R
import com.brewdog.catamap.constants.AnnotationConstants
import com.brewdog.catamap.domain.annotation.LayerChangeListener
import com.brewdog.catamap.domain.annotation.LayerManager
import com.brewdog.catamap.domain.annotation.models.Layer
import com.brewdog.catamap.domain.annotation.tools.ToolType
import com.brewdog.catamap.domain.annotation.tools.ToolType.Companion.getLabel
import com.brewdog.catamap.domain.annotation.tools.ToolType.Companion.showsColor
import com.brewdog.catamap.domain.annotation.tools.ToolsManager
import com.brewdog.catamap.domain.annotation.tools.ToolsStateListener
import com.brewdog.catamap.utils.logging.Logger
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

class ToolsOverlay : Fragment(), ToolsStateListener, LayerChangeListener {

    companion object {
        private const val TAG = "ToolsOverlay"
        private const val ARG_MAP_ID = "map_id"

        fun newInstance(mapId: String, toolsManager: ToolsManager, layerManager: LayerManager): ToolsOverlay {
            return ToolsOverlay().apply {
                arguments = Bundle().apply {
                    putString(ARG_MAP_ID, mapId)
                }
                this.toolsManager = toolsManager
                this.layerManager = layerManager
            }
        }
    }

    // Managers (injectés)
    private lateinit var toolsManager: ToolsManager
    private lateinit var layerManager: LayerManager

    // Views - Indicateurs
    private lateinit var indicatorBar: View
    private lateinit var toolIndicatorIcon: ImageView
    private lateinit var toolIndicatorText: TextView
    private lateinit var activeLayerText: TextView
    private lateinit var colorSeparator: View
    private lateinit var activeColorIndicator: View

    // Views - Paramètres contextuels
    private lateinit var contextualParametersContainer: LinearLayout
    private lateinit var strokeWidthContainer: LinearLayout
    private lateinit var strokeWidthSeekBar: SeekBar
    private lateinit var strokeWidthValue: TextView
    private lateinit var textSizeContainer: LinearLayout
    private lateinit var textSizeSpinner: Spinner

    // Views - Barre d'outils
    private lateinit var toolBar: View
    private lateinit var btnToolText: View
    private lateinit var btnToolDraw: View
    private lateinit var btnToolColor: View
    private lateinit var btnToolEraser: View
    private lateinit var btnToolClose: View
    private lateinit var iconToolText: ImageView
    private lateinit var iconToolDraw: ImageView
    private lateinit var iconToolColor: ImageView
    private lateinit var iconToolEraser: ImageView
    private lateinit var iconToolClose: ImageView
    private lateinit var eraserSizeSeekBar: SeekBar  // ← NOUVEAU
    private lateinit var eraserSizeLabel: TextView
    private lateinit var eraserSizeContainer: LinearLayout
    private lateinit var eraserSizeValue: TextView
    // État
    private var isVisible = false


    // Callback pour gérer le bouton Retour
    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Logger.entry(TAG, "onCreateView")
        return inflater.inflate(R.layout.tools_overlay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.entry(TAG, "onViewCreated")

        initViews(view)
        setupWindowInsets(view)
        setupListeners()
        setupTextSizeSpinner()
        setupBackPressedHandler()

        // S'abonner aux changements d'état
        toolsManager.addListener(this)
        layerManager.addListener(this)

        // Initialiser le calque actif dans ToolsManager
        val activeLayer = layerManager.getActiveLayer()
        if (activeLayer != null) {
            toolsManager.setActiveLayer(activeLayer.id, activeLayer.name)
            Logger.d(TAG, "Initial active layer: ${activeLayer.name}")
        }

        // Afficher l'état initial
        updateIndicators()
        updateToolButtons()
        updateContextualParameters()

        // Animation d'entrée
        animateIn()
    }

    private fun initViews(view: View) {
        // Indicateurs
        indicatorBar = view.findViewById(R.id.indicatorBar)
        toolIndicatorIcon = view.findViewById(R.id.toolIndicatorIcon)
        toolIndicatorText = view.findViewById(R.id.toolIndicatorText)
        activeLayerText = view.findViewById(R.id.activeLayerText)
        colorSeparator = view.findViewById(R.id.colorSeparator)
        activeColorIndicator = view.findViewById(R.id.activeColorIndicator)

        // Paramètres contextuels
        contextualParametersContainer = view.findViewById(R.id.contextualParametersContainer)
        strokeWidthContainer = view.findViewById(R.id.strokeWidthContainer)
        strokeWidthSeekBar = view.findViewById(R.id.strokeWidthSeekBar)
        eraserSizeSeekBar = view.findViewById(R.id.eraserSizeSeekBar)
        eraserSizeLabel = view.findViewById(R.id.eraserSizeLabel)
        eraserSizeContainer = view.findViewById(R.id.eraserSizeContainer)
        eraserSizeValue = view.findViewById(R.id.eraserSizeValue)
        strokeWidthValue = view.findViewById(R.id.strokeWidthValue)
        textSizeContainer = view.findViewById(R.id.textSizeContainer)
        textSizeSpinner = view.findViewById(R.id.textSizeSpinner)

        // Barre d'outils
        toolBar = view.findViewById(R.id.toolBar)
        btnToolText = view.findViewById(R.id.btnToolText)
        btnToolDraw = view.findViewById(R.id.btnToolDraw)
        btnToolColor = view.findViewById(R.id.btnToolColor)
        btnToolEraser = view.findViewById(R.id.btnToolEraser)
        btnToolClose = view.findViewById(R.id.btnToolClose)
        iconToolText = view.findViewById(R.id.iconToolText)
        iconToolDraw = view.findViewById(R.id.iconToolDraw)
        iconToolColor = view.findViewById(R.id.iconToolColor)
        iconToolEraser = view.findViewById(R.id.iconToolEraser)
        iconToolClose = view.findViewById(R.id.iconToolClose)
    }

    private fun setupWindowInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Appliquer les marges système
            indicatorBar.apply {
                val params = layoutParams as ViewGroup.MarginLayoutParams
                params.topMargin = systemBars.top
                layoutParams = params
            }

            toolBar.apply {
                val params = layoutParams as ViewGroup.MarginLayoutParams
                params.bottomMargin = systemBars.bottom
                layoutParams = params
            }

            Logger.d(TAG, "Window insets applied: top=${systemBars.top}, bottom=${systemBars.bottom}")
            insets
        }
    }

    private fun setupListeners() {
        // Boutons d'outils
        btnToolText.setOnClickListener { onToolClicked(ToolType.TEXT) }
        btnToolDraw.setOnClickListener { onToolClicked(ToolType.DRAWING) }
        btnToolColor.setOnClickListener { onColorClicked() }
        btnToolEraser.setOnClickListener { onToolClicked(ToolType.ERASER) }
        btnToolClose.setOnClickListener { onCloseClicked() }

        // Slider épaisseur
        eraserSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    toolsManager.eraserSize = (progress + 10).toFloat() // 0-40 → 10-50
                    updateEraserSizeDisplay()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Spinner taille texte
        textSizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val size = AnnotationConstants.Text.SIZES[position]
                toolsManager.textSize = size
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTextSizeSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            AnnotationConstants.Text.SIZE_LABELS
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        textSizeSpinner.adapter = adapter

        // Sélectionner la taille actuelle
        val currentIndex = AnnotationConstants.Text.SIZES.indexOf(toolsManager.textSize)
        if (currentIndex >= 0) {
            textSizeSpinner.setSelection(currentIndex, false)
        }
    }

    /**
     * Configure le gestionnaire du bouton Retour Android
     */
    private fun setupBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        Logger.d(TAG, "Back press handler configured")
    }

    /**
     * Gère le bouton Retour selon l'état actuel
     */
    private fun handleBackPress() {
        val currentTool = toolsManager.activeTool

        if (currentTool != ToolType.NONE) {
            // Un outil est actif → le désactiver
            Logger.d(TAG, "Back pressed: deactivating tool ${currentTool.getLabel()}")
            toolsManager.clearTool()
        } else {
            // Aucun outil actif → fermer l'overlay
            Logger.d(TAG, "Back pressed: closing overlay")
            hide()
        }
    }

    // ========== Actions ==========

    private fun onToolClicked(tool: ToolType) {
        Logger.d(TAG, "Tool clicked: ${tool.getLabel()}")
        toolsManager.setTool(tool)
    }

    private fun onColorClicked() {
        Logger.d(TAG, "Color picker clicked")
        showColorPicker()
    }

    private fun onCloseClicked() {
        Logger.d(TAG, "Close clicked")
        hide()
    }

    private fun showColorPicker() {
        ColorPickerDialog.Builder(requireContext())
            .setTitle("Choisir une couleur")
            .setPositiveButton("Sélectionner", ColorEnvelopeListener { envelope, _ ->
                toolsManager.activeColor = envelope.color
                Logger.i(TAG, "Color selected: ${String.format("#%08X", envelope.color)}")
            })
            .setNegativeButton("Annuler") { dialog, _ ->
                dialog.dismiss()
            }
            .attachAlphaSlideBar(false)
            .setBottomSpace(12)
            .show()
    }

    // ========== UI Updates ==========

    private fun updateIndicators() {
        // Texte et icône de l'outil
        val tool = toolsManager.activeTool
        toolIndicatorText.text = tool.getLabel()

        val iconRes = when (tool) {
            ToolType.NONE -> R.drawable.ic_tool_text // Icône par défaut
            ToolType.TEXT -> R.drawable.ic_tool_text
            ToolType.DRAWING -> R.drawable.ic_tool_draw
            ToolType.ERASER -> R.drawable.ic_tool_eraser
        }
        toolIndicatorIcon.setImageResource(iconRes)

        // Calque actif
        val activeLayer = layerManager.getActiveLayer()
        activeLayerText.text = activeLayer?.name ?: "Aucun calque"

        // Couleur active
        val showColor = tool.showsColor()
        colorSeparator.visibility = if (showColor) View.VISIBLE else View.GONE
        activeColorIndicator.visibility = if (showColor) View.VISIBLE else View.GONE

        if (showColor) {
            updateColorIndicator(toolsManager.activeColor)
        }
    }

    private fun updateColorIndicator(color: Int) {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        drawable.setStroke(4, Color.WHITE)
        activeColorIndicator.background = drawable
    }

    private fun updateToolButtons() {
        val activeTool = toolsManager.activeTool

        // Mettre à jour l'apparence des boutons
        updateToolButton(btnToolText, iconToolText, activeTool == ToolType.TEXT)
        updateToolButton(btnToolDraw, iconToolDraw, activeTool == ToolType.DRAWING)
        updateToolButton(btnToolEraser, iconToolEraser, activeTool == ToolType.ERASER)

        // Couleur et Fermer ne sont jamais "actifs"
        updateToolButton(btnToolColor, iconToolColor, false)
        updateToolButton(btnToolClose, iconToolClose, false)
    }

    private fun updateToolButton(button: View, icon: ImageView, isActive: Boolean) {
        if (isActive) {
            button.setBackgroundColor(0x44FFFFFF) // Semi-transparent blanc
            icon.setColorFilter(0xFFFFD700.toInt()) // Or
        } else {
            button.setBackgroundColor(Color.TRANSPARENT)
            icon.setColorFilter(Color.WHITE)
        }
    }

    private fun updateContextualParameters() {
        val tool = toolsManager.activeTool
        when (tool) {
            ToolType.TEXT -> {
                contextualParametersContainer.visibility = View.VISIBLE
                textSizeContainer.visibility = View.VISIBLE
                strokeWidthContainer.visibility = View.GONE
            }
            ToolType.DRAWING -> {
                contextualParametersContainer.visibility = View.VISIBLE
                textSizeContainer.visibility = View.GONE
                strokeWidthContainer.visibility = View.VISIBLE
                strokeWidthSeekBar.visibility = View.VISIBLE
                eraserSizeSeekBar.visibility = View.GONE
                eraserSizeLabel.visibility = View.GONE
                updateStrokeWidthDisplay()
            }
            ToolType.ERASER -> {
                contextualParametersContainer.visibility = View.VISIBLE
                textSizeContainer.visibility = View.GONE
                strokeWidthContainer.visibility = View.VISIBLE
                strokeWidthSeekBar.visibility = View.GONE
                eraserSizeSeekBar.visibility = View.VISIBLE
                eraserSizeLabel.visibility = View.VISIBLE

                // Synchroniser avec la valeur actuelle
                eraserSizeSeekBar.progress = toolsManager.eraserSize.toInt()
                updateEraserSizeDisplay()
            }
            else -> {
                contextualParametersContainer.visibility = View.GONE
                textSizeContainer.visibility = View.GONE
                strokeWidthContainer.visibility = View.GONE
            }
        }
    }

    private fun updateStrokeWidthDisplay() {
        val width = toolsManager.strokeWidth
        strokeWidthSeekBar.progress = (width - 1).toInt() // 1-20 → 0-19
        strokeWidthValue.text = "${width.toInt()}px"
    }

    private fun updateEraserSizeDisplay() {
        val size = toolsManager.eraserSize
        eraserSizeSeekBar.progress = (size - 10).toInt() // 10-50 → 0-40
        eraserSizeValue.text = "${size.toInt()}px"
    }


    // ========== ToolsStateListener ==========

    override fun onToolChanged(previous: ToolType, current: ToolType) {
        Logger.d(TAG, "Tool changed: ${previous.getLabel()} → ${current.getLabel()}")
        updateIndicators()
        updateToolButtons()
        updateContextualParameters()
    }

    override fun onColorChanged(color: Int) {
        Logger.d(TAG, "Color changed: ${String.format("#%08X", color)}")
        updateColorIndicator(color)
    }

    override fun onTextSizeChanged(size: Int) {
        Logger.d(TAG, "Text size changed: ${size}sp")
        // Le spinner est déjà synchronisé
    }

    override fun onStrokeWidthChanged(width: Float) {
        Logger.d(TAG, "Stroke width changed: ${width}px")
        updateStrokeWidthDisplay()
    }

    // ========== LayerChangeListener ==========

    override fun onLayersChanged(layers: List<Layer>, activeLayerId: String) {
        Logger.d(TAG, "Layers changed: ${layers.size} layers, active=$activeLayerId")

        // Mettre à jour le nom du calque actif dans le bandeau
        val activeLayer = layers.find { it.id == activeLayerId }
        activeLayerText.text = activeLayer?.name ?: "Aucun calque"

        // Sauvegarder dans ToolsManager pour utilisation future (dessin/texte)
        if (activeLayer != null) {
            toolsManager.setActiveLayer(activeLayer.id, activeLayer.name)
        }
    }

    // ========== Animation ==========

    private fun animateIn() {
        view?.let { v ->
            val animation = TranslateAnimation(
                0f, 0f,
                v.height.toFloat(), 0f
            )
            animation.duration = AnnotationConstants.ToolsOverlay.ANIMATION_DURATION_MS
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    isVisible = true
                }
                override fun onAnimationEnd(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            v.startAnimation(animation)
        }
    }

    fun hide() {
        view?.let { v ->
            val animation = TranslateAnimation(
                0f, 0f,
                0f, v.height.toFloat()
            )
            animation.duration = AnnotationConstants.ToolsOverlay.ANIMATION_DURATION_MS
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    isVisible = false
                    // Notifier MainActivity de fermer l'overlay
                    (activity as? ToolsOverlayListener)?.onToolsOverlayClosed()
                }
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            v.startAnimation(animation)
        }
    }

    override fun onDestroyView() {
        toolsManager.removeListener(this)
        layerManager.removeListener(this)
        toolsManager.clearActiveLayer()
        backPressedCallback.remove()
        super.onDestroyView()
    }
}

/**
 * Interface pour communiquer avec l'activité
 */
interface ToolsOverlayListener {
    fun onToolsOverlayClosed()
}
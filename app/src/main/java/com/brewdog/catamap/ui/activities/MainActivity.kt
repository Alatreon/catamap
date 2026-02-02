package com.brewdog.catamap.ui.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.brewdog.catamap.R
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.models.MapItem
import com.brewdog.catamap.data.repository.AnnotationRepository
import com.brewdog.catamap.data.repository.MapRepository
import com.brewdog.catamap.domain.annotation.LayerManager
import com.brewdog.catamap.domain.compass.CompassManager
import com.brewdog.catamap.domain.map.MapLoader
import com.brewdog.catamap.domain.map.MapViewController
import com.brewdog.catamap.domain.minimap.MinimapController
import com.brewdog.catamap.ui.adapters.AccessibleSubsamplingImageView
import com.brewdog.catamap.ui.adapters.MinimapView
import com.brewdog.catamap.ui.views.RotationGestureDetector
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.brewdog.catamap.ui.annotation.EditBottomSheet
import com.brewdog.catamap.domain.annotation.tools.ToolsManager
import com.brewdog.catamap.ui.annotation.AnnotationOverlay
import com.brewdog.catamap.ui.annotation.tools.ToolsOverlay
import com.brewdog.catamap.ui.annotation.tools.ToolsOverlayListener
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch


/**
 * Activité principale de l'application - VERSION REFACTORISÉE
 *
 * Architecture:
 * - MapRepository: Gestion des données
 * - MapViewController: Contrôle de la carte
 * - CompassManager: Gestion de la boussole
 * - MinimapController: Gestion de la minimap
 * - MapLoader: Gestion du chargement
 * - RotationGestureDetector: Détection des gestes
 */
class MainActivity : AppCompatActivity(), ToolsOverlayListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Repository
    private lateinit var repository: MapRepository
    private lateinit var annotationRepository: AnnotationRepository

    // Controllers
    private lateinit var mapViewController: MapViewController
    private lateinit var compassManager: CompassManager
    private lateinit var minimapController: MinimapController
    private lateinit var mapLoader: MapLoader

    // Views
    private lateinit var mapView: AccessibleSubsamplingImageView
    private lateinit var compassView: ImageView
    private lateinit var minimapView: MinimapView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var menuButton: ImageButton

    // État
    private var rotateWithCompass = false
    private var manualRotateEnabled = false
    private var minimapEnabled = false
    private lateinit var rotationDetector: RotationGestureDetector
    private var batterySaverEnabled = false
    private var editBottomSheet: EditBottomSheet? = null
    private lateinit var toolsManager: ToolsManager
    private var toolsOverlay: ToolsOverlay? = null
    private var layerManager: LayerManager? = null
    // Sauvegarde de l'état avant mode édition
    private var preEditState: PreEditState? = null
    private var annotationOverlay: AnnotationOverlay? = null
    private var isDarkMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.entry(TAG, "onCreate")

        setContentView(R.layout.activity_main)

        // Initialiser les vues
        initViews()

        // Initialiser les composants
        initRepository()
        initControllers()
        setupMinimapSize()

        // Configuration
        setupWindowInsets()
        setupGestureDetectors()
        setupMenu()

        // Charger la carte
        loadInitialMap()
        // Initialiser le ToolsManager
        toolsManager = ToolsManager(this)

        Logger.exit(TAG, "onCreate")
    }

    /**
     * Initialise toutes les vues
     */
    private fun initViews() {
        Logger.entry(TAG, "initViews")

        mapView = findViewById(R.id.mapView)
        compassView = findViewById(R.id.compassView)
        minimapView = findViewById(R.id.minimapView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        menuButton = findViewById(R.id.menuButton)

        Logger.d(TAG, "All views initialized")
        Logger.exit(TAG, "initViews")
    }

    /**
     * Initialise le repository
     */
    private fun initRepository() {
        Logger.entry(TAG, "initRepository")

        repository = MapRepository(this)
        annotationRepository = AnnotationRepository(this)  // ← NOUVEAU

        Logger.i(TAG, "Repositories initialized")
        Logger.exit(TAG, "initRepository")
    }

    /**
     * Initialise tous les contrôleurs
     */
    private fun initControllers() {
        Logger.entry(TAG, "initControllers")

        // MapLoader
        mapLoader = MapLoader(loadingOverlay).apply {
            onLoadSuccess = { map ->
                Logger.i(TAG, "Map loaded successfully: ${map.name}")
                if (minimapEnabled) {
                    loadMinimapForCurrentMap()
                }
            }
            onLoadError = { map, exception ->
                Logger.e(TAG, "Failed to load map: ${map.name}", exception)
                // TODO: Afficher un message d'erreur à l'utilisateur
            }
        }

        // MapViewController
        mapViewController = MapViewController(this, mapView).apply {
            onMapReady = {
                Logger.i(TAG, "Map ready callback")
                mapLoader.onSuccess()

                // Mettre à jour la minimap
                if (minimapEnabled) {
                    val (width, height) = getMapDimensions()
                    minimapController.setMapDimensions(width, height)
                    minimapController.forceUpdateViewport()
                }
            }
            onMapLoadError = { exception ->
                Logger.e(TAG, "Map load error callback", exception)
                mapLoader.onError(exception)
            }
        }

        // CompassManager
        compassManager = CompassManager(
            context = this,
            compassView = compassView,
            onRotationChanged = { angle ->
                mapViewController.setRotation(angle, rotateWithCompass)
            }
        )

        // MinimapController
        minimapController = MinimapController(
            context = this,
            minimapView = minimapView,
            mainMapView = mapView
        )

        Logger.i(TAG, "All controllers initialized")
        Logger.exit(TAG, "initControllers")
    }

    /**
     * Configure les window insets (notch, navigation bar)
     */
    private fun setupWindowInsets() {
        Logger.entry(TAG, "setupWindowInsets")

        val rootContainer = findViewById<FrameLayout>(R.id.rootContainer)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            menuButton.apply {
                val params = layoutParams as FrameLayout.LayoutParams
                params.topMargin = systemBars.top + 16
                params.rightMargin = systemBars.right + 16
                layoutParams = params
            }
            compassView.apply {
                val params = layoutParams as FrameLayout.LayoutParams
                params.topMargin = systemBars.top + 16   // Même hauteur que menuButton
                params.rightMargin = systemBars.right + 8 // 8dp de marge (selon votre XML)
                layoutParams = params
            }

            Logger.d(TAG, "Window insets applied: top=${systemBars.top}, right=${systemBars.right}")
            insets
        }

        Logger.exit(TAG, "setupWindowInsets")
    }

    /**
     * Configure les détecteurs de gestes
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetectors() {
        Logger.entry(TAG, "setupGestureDetectors")

        rotationDetector = RotationGestureDetector { angle ->
            if (!rotateWithCompass && manualRotateEnabled) {
                Logger.v(TAG, "Manual rotation: $angle°")
                val newRotation = mapViewController.getRotation() + angle
                mapViewController.setRotation(newRotation, manualRotateEnabled)
            }
        }

        mapView.setOnTouchListener { _, event ->
            rotationDetector.onTouchEvent(event)
            false
        }

        mapView.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onScaleChanged(newScale: Float, origin: Int) {
                annotationOverlay?.refresh()
                // Appelé pendant zoom (pinch, double-tap, etc.)
                if (minimapEnabled) {
                    minimapController.updateViewport()
                }
            }

            override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                annotationOverlay?.refresh()
                // Appelé pendant pan (déplacement)
                if (minimapEnabled) {
                    minimapController.updateViewport()
                }
            }
        })

        Logger.exit(TAG, "setupGestureDetectors")
    }

    /**
     * Configure le menu
     */
    private fun setupMenu() {
        Logger.entry(TAG, "setupMenu")

        menuButton.setOnClickListener {
            Logger.d(TAG, "Menu button clicked")
            showMenu(menuButton)
        }

        Logger.exit(TAG, "setupMenu")
    }

    /**
     * Affiche le menu popup
     */
    private fun showMenu(anchor: ImageButton) {
        Logger.entry(TAG, "showMenu")

        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menuInflater.inflate(R.menu.main_menu, popupMenu.menu)

        // Mettre à jour les états des items
        popupMenu.menu.apply {
            findItem(R.id.menu_dark_mode)?.isChecked = mapViewController.isDarkModeEnabled()
            findItem(R.id.menu_rotate_compass)?.isChecked = rotateWithCompass
            findItem(R.id.menu_rotate_manual)?.isChecked = manualRotateEnabled
            findItem(R.id.menu_battery_saver)?.isChecked = batterySaverEnabled
            findItem(R.id.menu_minimap)?.isChecked = minimapEnabled
            findItem(R.id.menu_reset_rotation)?.isVisible = manualRotateEnabled
            findItem(R.id.menu_rotate_compass)?.isEnabled = !batterySaverEnabled

            if (batterySaverEnabled) {
                findItem(R.id.menu_rotate_compass)?.isEnabled = false
                findItem(R.id.menu_rotate_manual)?.isEnabled = false
            } else {
                findItem(R.id.menu_rotate_compass)?.isEnabled = true
                findItem(R.id.menu_rotate_manual)?.isEnabled = true
            }


            // Désactiver le mode indisponible
            val currentMap = mapViewController.getCurrentMap()
            currentMap?.let { map ->
                findItem(R.id.menu_dark_mode)?.isEnabled = map.hasLightMode && map.hasDarkMode
            }
        }

        popupMenu.setOnMenuItemClickListener { item ->
            handleMenuItemClick(item.itemId)
            true
        }

        popupMenu.show()
        Logger.exit(TAG, "showMenu")
    }

    /**
     * Gère les clics sur les items du menu
     */
    private fun handleMenuItemClick(itemId: Int) {
        Logger.entry(TAG, "handleMenuItemClick", itemId)

        when (itemId) {
            R.id.menu_rotate_compass -> toggleCompassLock()
            R.id.menu_rotate_manual -> toggleManualRotate()
            R.id.menu_dark_mode -> toggleDarkMode()
            R.id.menu_minimap -> toggleMinimap()
            R.id.menu_map_manager -> openMapManager()
            R.id.menu_reset_rotation -> resetRotation()
            R.id.menu_battery_saver -> toggleBatterySaver()
            R.id.menu_edit_map -> {                openEditMode()            }
        }

        Logger.exit(TAG, "handleMenuItemClick")
    }

    /**
     * Charge la carte initiale
     */
    private fun loadInitialMap() {
        Logger.entry(TAG, "loadInitialMap")

        // Nettoyer l'ancien LayerManager si on change de carte
        layerManager?.cleanup()
        layerManager = null

        val database = repository.loadDatabase()

        // Récupérer la carte depuis l'intent ou la carte par défaut
        val selectedMapId = intent.getStringExtra(AppConstants.Intent.EXTRA_SELECTED_MAP_ID)
        val map = if (selectedMapId != null) {
            database.getMapById(selectedMapId).also {
                Logger.i(TAG, "Loading map from intent: ${it?.name}")
            }
        } else {
            database.getDefaultMap().also {
                Logger.i(TAG, "Loading default map: ${it?.name}")
            }
        } ?: database.maps.firstOrNull().also {
            Logger.i(TAG, "Loading first available map: ${it?.name}")
        }

        if (map == null) {
            Logger.e(TAG, "No map available to load")
            // TODO: Afficher un message d'erreur
            return
        }

        // Charger la carte
        mapLoader.startLoading(map)
        mapViewController.loadMap(map, darkMode = true)

        layerManager = LayerManager(annotationRepository, lifecycleScope)
        lifecycleScope.launch {
            layerManager!!.loadAnnotations(map.id)

            // Afficher l'overlay après chargement des annotations
            withContext(Dispatchers.Main) {
                showAnnotationOverlay()
            }
        }


        Logger.exit(TAG, "loadInitialMap")
    }

    /**
     * Charge la minimap pour la carte actuelle
     */
    private fun loadMinimapForCurrentMap() {
        Logger.entry(TAG, "loadMinimapForCurrentMap")

        val map = mapViewController.getCurrentMap()
        if (map == null) {
            Logger.w(TAG, "No current map to load minimap for")
            return
        }

        val isDarkMode = mapViewController.isDarkModeEnabled()
        val minimapUri = map.getMinimapUri(isDarkMode)

        if (minimapUri == null) {
            Logger.w(TAG, "No minimap available for current mode")
            // TODO: Générer la minimap à la volée
            return
        }

        minimapController.loadMinimapImage(minimapUri)
        minimapView.postDelayed({
            minimapController.forceUpdateViewport()
        }, 100)

        Logger.i(TAG, "Minimap loaded for ${map.name} (${if (isDarkMode) "dark" else "light"} mode)")

        Logger.exit(TAG, "loadMinimapForCurrentMap")
    }

    /**
     * Affiche l'overlay d'annotations (permanent)
     * Appelé une seule fois au chargement de la carte
     */
    private fun showAnnotationOverlay() {
        Logger.entry(TAG, "showAnnotationOverlay")
        Logger.d(TAG, "annotationOverlay is null: ${annotationOverlay == null}")
        val currentMap = mapViewController.getCurrentMap()
        if (currentMap == null) {
            Logger.w(TAG, "No map to show annotations for")
            return
        }

        if (layerManager == null) {
            Logger.e(TAG, "LayerManager not available")
            return
        }

        // Ne créer l'overlay qu'une seule fois
        if (annotationOverlay == null) {
            annotationOverlay = AnnotationOverlay.newInstance(
                mapId = currentMap.id,
                isDarkMode = mapViewController.isDarkModeEnabled(),
                toolsManager = toolsManager,
                layerManager = layerManager!!,
                mapView = mapView
            )

            // Ajouter AVANT loadingOverlay pour que le loader soit par-dessus
            supportFragmentManager.beginTransaction()
                .add(R.id.rootContainer, annotationOverlay!!, "AnnotationOverlay")
                .commit()

            Logger.i(TAG, "AnnotationOverlay created (permanent)")
        }else {
            // SI CETTE BRANCHE S'EXÉCUTE, il y a un problème
            Logger.w(TAG, "AnnotationOverlay already exists, skipping creation")
        }

        Logger.exit(TAG, "showAnnotationOverlay")
    }

    // ========== MENU ACTIONS ==========

    private fun toggleCompassLock() {
        Logger.entry(TAG, "toggleCompassLock")
        rotateWithCompass = !rotateWithCompass
        compassManager.setRotateWithCompass(rotateWithCompass)
        if (rotateWithCompass) {
            mapViewController.setRotationEnabled(true)
        } else if (!manualRotateEnabled) {
            mapViewController.setRotationEnabled(false)
        }
        Logger.i(TAG, "Compass lock: $rotateWithCompass")
        Logger.exit(TAG, "toggleCompassLock")
    }

    private fun toggleManualRotate() {
        Logger.entry(TAG, "toggleManualRotate")
        manualRotateEnabled = !manualRotateEnabled
        if (manualRotateEnabled) {
            mapViewController.setRotationEnabled(true)
        } else if (!rotateWithCompass) {
            mapViewController.setRotationEnabled(false)
        }
        Logger.i(TAG, "Manual rotate: $manualRotateEnabled")
        Logger.exit(TAG, "toggleManualRotate")
    }

    private fun toggleDarkMode() {
        Logger.entry(TAG, "toggleDarkMode")

        val newMode = !mapViewController.isDarkModeEnabled()

        annotationOverlay?.updateDarkMode(newMode)


        Logger.i(TAG, "Toggling dark mode: ${mapViewController.isDarkModeEnabled()} → $newMode")

        loadingOverlay.visibility = android.view.View.VISIBLE
        loadingOverlay.alpha = 1f

        val originalOnMapReady = mapViewController.onMapReady
        mapViewController.onMapReady = {
            // Cacher le loader avec animation
            loadingOverlay.animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction {
                    loadingOverlay.visibility = android.view.View.GONE
                }
                .start()

            // Appeler le callback original
            originalOnMapReady?.invoke()

            // Restaurer le callback original
            mapViewController.onMapReady = originalOnMapReady
        }

        // Changer le mode
        mapViewController.switchMode(newMode)

        // Mettre à jour la minimap si activée
        if (minimapEnabled) {
            loadMinimapForCurrentMap()
        }

        Logger.i(TAG, "Dark mode toggled to: $newMode")
        Logger.exit(TAG, "toggleDarkMode")
    }

    private fun toggleMinimap() {
        Logger.entry(TAG, "toggleMinimap")
        minimapEnabled = !minimapEnabled
        minimapController.setEnabled(minimapEnabled)
        if (minimapEnabled) {
            loadMinimapForCurrentMap()
        }
        Logger.i(TAG, "Minimap: $minimapEnabled")
        Logger.exit(TAG, "toggleMinimap")
    }

    private fun openMapManager() {
        Logger.entry(TAG, "openMapManager")
        val intent = Intent(this, MapManagerActivity::class.java)
        startActivity(intent)
        Logger.exit(TAG, "openMapManager")
    }

    /**
     * Réinitialise la rotation à 0°
     */
    private fun resetRotation() {
        Logger.entry(TAG, "resetRotation")

        if (manualRotateEnabled) {
            mapViewController.setRotation(0f, manualRotateEnabled)

            Logger.i(TAG, "Rotation reset to 0°")
        } else {
            Logger.w(TAG, "Reset rotation called but manual rotate not enabled")
        }

        Logger.exit(TAG, "resetRotation")
    }

    private fun toggleBatterySaver() {
        Logger.entry(TAG, "toggleBatterySaver")

        batterySaverEnabled = !batterySaverEnabled

        if (batterySaverEnabled) {
            // MODE ÉCONOMIE ACTIVÉ
            rotateWithCompass = false
            manualRotateEnabled = false
            compassView.visibility = android.view.View.GONE
            compassManager.unregister()
            mapViewController.setRotation(0f, false)
            mapViewController.setRotationEnabled(false)
            compassManager.setBatterySaverEnabled(true)

            Logger.i(TAG, "Battery saver ENABLED: compass off, rotation reset")
        } else {
            // MODE ÉCONOMIE DÉSACTIVÉ
            rotateWithCompass = false
            manualRotateEnabled = false
            compassManager.setRotateWithCompass(false)
            compassManager.setBatterySaverEnabled(false)
            compassView.visibility = android.view.View.VISIBLE
            compassManager.register()

            Logger.i(TAG, "Battery saver DISABLED: compass on")
        }

        Logger.exit(TAG, "toggleBatterySaver")
    }

    // ========== LIFECYCLE ==========
    override fun onResume() {
        super.onResume()
        Logger.entry(TAG, "onResume")

        if (!batterySaverEnabled) {
            compassManager.register()
        } else {
            Logger.d(TAG, "Battery saver active, compass not registered")
        }

        // Recharger la database au cas où elle aurait changé
        val database = repository.loadDatabase()
        database.logState()

        Logger.exit(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Logger.entry(TAG, "onPause")

        compassManager.unregister()

        Logger.exit(TAG, "onPause")
    }

    override fun onDestroy() {
        Logger.entry(TAG, "onDestroy")

        // Cleanup du LayerManager
        layerManager?.let { manager ->
            manager.cleanup()
            Logger.i(TAG, "LayerManager cleaned up")
        }
        layerManager = null
        annotationRepository.cleanup()

        try {
            compassManager.unregister()
            mapViewController.cleanup()
            minimapController.cleanup()
            mapLoader.reset()
        } catch (e: Exception) {
            Logger.e(TAG, "Error during cleanup", e)
        }
        // Cleanup overlay
        hideToolsOverlay()

        super.onDestroy()
        Logger.exit(TAG, "onDestroy")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logger.entry(TAG, "onNewIntent")

        setIntent(intent)

        // Si une nouvelle carte est sélectionnée, la charger
        val selectedMapId = intent.getStringExtra(AppConstants.Intent.EXTRA_SELECTED_MAP_ID)
        if (selectedMapId != null) {
            Logger.i(TAG, "Loading new map from intent: $selectedMapId")
            val database = repository.loadDatabase()
            val map = database.getMapById(selectedMapId)
            if (map != null) {
                loadNewMap(map)
            }
        }

        Logger.exit(TAG, "onNewIntent")
    }

    /**
     * Charge une nouvelle carte
     * Gère le cleanup des annotations de l'ancienne carte
     */
    private fun loadNewMap(map: MapItem) {
        Logger.entry(TAG, "loadNewMap", map.name)

        // 1. Supprimer l'overlay actuel
        annotationOverlay?.let { overlay ->
            supportFragmentManager.beginTransaction()
                .remove(overlay)
                .commit()
        }
        annotationOverlay = null

        // 2. Cleanup LayerManager
        layerManager?.cleanup()
        layerManager = null

        // 3. Charger la nouvelle carte
        mapLoader.startLoading(map)
        mapViewController.loadMap(map, mapViewController.isDarkModeEnabled())

        // 4. Créer nouveau LayerManager
        layerManager = LayerManager(annotationRepository, lifecycleScope)
        lifecycleScope.launch {
            layerManager!!.loadAnnotations(map.id)

            // 5. Créer nouvel overlay
            withContext(Dispatchers.Main) {
                showAnnotationOverlay()
            }
        }

        Logger.i(TAG, "New map loaded with annotations")
    }

    private fun setupMinimapSize() {
        val screenWidth = resources.displayMetrics.widthPixels
        val minimapSize = (screenWidth * MinimapView.SCREEN_PERCENT).toInt()
        val params = minimapView.layoutParams as FrameLayout.LayoutParams
        params.width = minimapSize
        params.height = minimapSize
        minimapView.layoutParams = params
    }

    /**
     * Ouvre le mode édition
     * Désactive la rotation si active, puis affiche le Bottom Sheet
     */
    private fun openEditMode() {
        Logger.entry(TAG, "openEditMode")

        // Désactiver la rotation si active
        if (rotateWithCompass) {
            rotateWithCompass = false
            compassManager.setRotateWithCompass(false)
            mapViewController.setRotation(0f, false)
            Logger.i(TAG, "Compass rotation disabled for edit mode")
        }

        if (manualRotateEnabled) {
            manualRotateEnabled = false
            mapViewController.setRotation(0f, false)
            Logger.i(TAG, "Manual rotation disabled for edit mode")
        }

        // Récupérer la carte actuelle
        val currentMap = mapViewController.getCurrentMap()

        if (currentMap == null) {
            Toast.makeText(this, "Aucune carte chargée", Toast.LENGTH_SHORT).show()
            Logger.w(TAG, "No map loaded, cannot open edit mode")
            return
        }

        // Afficher le Bottom Sheet
        showEditBottomSheet(currentMap.id)
    }

    /**
     * Affiche le Bottom Sheet d'édition
     */
    private fun showEditBottomSheet(mapId: String) {
        Logger.entry(TAG, "showEditBottomSheet", mapId)

        editBottomSheet?.dismiss()

        // Passer le LayerManager existant au EditBottomSheet
        if (layerManager == null) {
            Logger.e(TAG, "LayerManager not available")
            return
        }

        editBottomSheet = EditBottomSheet.newInstance(mapId, layerManager!!)
        editBottomSheet!!.show(supportFragmentManager, "EditBottomSheet")

        Logger.i(TAG, "Edit bottom sheet shown with shared LayerManager")
    }

    override fun onToolsOverlayClosed() {
        Logger.entry(TAG, "onToolsOverlayClosed")

        // Fermer l'overlay
        hideToolsOverlay()

        // Réouvrir le Bottom Sheet d'édition
        val currentMap = mapViewController.getCurrentMap()
        if (currentMap != null) {
            showEditBottomSheet(currentMap.id)
        }
    }

    /**
     * Affiche l'overlay des outils
     */
    /*fun showToolsOverlay() {
        Logger.entry(TAG, "showToolsOverlay")
        // AJOUTER CE LOG
        Logger.d(TAG, "annotationOverlay before: $annotationOverlay")


        val currentMap = mapViewController.getCurrentMap()
        if (currentMap == null) {
            Logger.w(TAG, "No map loaded")
            return
        }

        if (layerManager == null) {
            Logger.e(TAG, "LayerManager not available")
            return
        }

        enterEditMode()

        val activeLayer = layerManager!!.getActiveLayer()
        if (activeLayer == null) {
            Logger.w(TAG, "No active layer available")
            exitEditMode()
            return
        }

        // Créer l'overlay d'annotations
        annotationOverlay = AnnotationOverlay.newInstance(
            mapId = currentMap.id,
            isDarkMode = mapViewController.isDarkModeEnabled(),
            toolsManager = toolsManager,
            layerManager = layerManager!!,
            mapView = mapView
        )

        // Ajouter l'overlay d'annotations au rootContainer
        supportFragmentManager.beginTransaction()
            .add(R.id.rootContainer, annotationOverlay!!, "AnnotationOverlay")
            .commit()

        // Créer et ajouter l'overlay des outils
        toolsOverlay = ToolsOverlay.newInstance(currentMap.id, toolsManager, layerManager!!)

        supportFragmentManager.beginTransaction()
            .add(R.id.rootContainer, toolsOverlay!!, "ToolsOverlay")
            .commit()

        Logger.i(TAG, "Tools overlay and annotation overlay shown")
    }*/
    fun showToolsOverlay() {
        Logger.entry(TAG, "showToolsOverlay")

        // AJOUTER CE LOG
        Logger.d(TAG, "annotationOverlay before: $annotationOverlay")

        val currentMap = mapViewController.getCurrentMap()
        if (currentMap == null) {
            Logger.w(TAG, "No map loaded")
            return
        }

        if (layerManager == null) {
            Logger.e(TAG, "LayerManager not available")
            return
        }

        enterEditMode()

        val activeLayer = layerManager!!.getActiveLayer()
        if (activeLayer == null) {
            Logger.w(TAG, "No active layer available")
            exitEditMode()
            return
        }

        // ✅ CORRECTION : NE PLUS CRÉER L'OVERLAY ICI
        // L'overlay existe déjà (créé au démarrage)

        // Créer et ajouter seulement l'overlay des outils
        toolsOverlay = ToolsOverlay.newInstance(currentMap.id, toolsManager, layerManager!!)

        supportFragmentManager.beginTransaction()
            .add(R.id.rootContainer, toolsOverlay!!, "ToolsOverlay")
            .commit()

        Logger.i(TAG, "Tools overlay shown (annotations already visible)")
    }






    /**
     * Masque l'overlay des outils
     */
    fun hideToolsOverlay() {
        Logger.entry(TAG, "hideToolsOverlay")

        // Fermer seulement ToolsOverlay
        toolsOverlay?.let { overlay ->
            supportFragmentManager.beginTransaction()
                .remove(overlay)
                .commit()
        }
        toolsOverlay = null

        toolsManager.clearTool()

        Logger.i(TAG, "Tools hidden, annotations still visible")

        exitEditMode()
    }


    /**
     * Entre en mode édition
     * Sauvegarde l'état actuel et désactive les éléments UI
     */
    private fun enterEditMode() {
        Logger.entry(TAG, "enterEditMode")

        // Sauvegarder l'état actuel
        preEditState = PreEditState(
            minimapEnabled = minimapEnabled,
            rotateWithCompass = rotateWithCompass,
            manualRotateEnabled = manualRotateEnabled
        )

        Logger.d(TAG, "State saved: $preEditState")

        // Masquer les éléments UI
        menuButton.visibility = View.GONE
        compassView.visibility = View.GONE

        // Désactiver la minimap si active
        if (minimapEnabled) {
            minimapEnabled = false
            minimapController.setEnabled(false)
            Logger.d(TAG, "Minimap disabled")
        }

        // Désactiver la rotation boussole si active
        if (rotateWithCompass) {
            rotateWithCompass = false
            compassManager.setRotateWithCompass(false)
            mapViewController.setRotation(0f, false)
            Logger.d(TAG, "Compass rotation disabled")
        }

        // Désactiver la rotation manuelle si active
        if (manualRotateEnabled) {
            manualRotateEnabled = false
            mapViewController.setRotationEnabled(false)
            mapViewController.setRotation(0f, false)
            Logger.d(TAG, "Manual rotation disabled")
        }

        Logger.i(TAG, "Edit mode entered")
    }

    /**
     * Sort du mode édition
     * Restaure l'état sauvegardé et réactive les éléments UI
     */
    private fun exitEditMode() {
        Logger.entry(TAG, "exitEditMode")

        val state = preEditState
        if (state == null) {
            Logger.w(TAG, "No state to restore")
            return
        }

        Logger.d(TAG, "Restoring state: $state")

        // Réafficher les éléments UI
        menuButton.visibility = View.VISIBLE
        compassView.visibility = View.VISIBLE

        // Restaurer la minimap
        minimapEnabled = state.minimapEnabled
        minimapController.setEnabled(state.minimapEnabled)
        if (state.minimapEnabled) {
            Logger.d(TAG, "Minimap restored")
        }

        // Restaurer la rotation boussole
        rotateWithCompass = state.rotateWithCompass
        compassManager.setRotateWithCompass(state.rotateWithCompass)
        if (state.rotateWithCompass) {
            mapViewController.setRotationEnabled(true)
            Logger.d(TAG, "Compass rotation restored")
        }

        // Restaurer la rotation manuelle
        manualRotateEnabled = state.manualRotateEnabled
        if (state.manualRotateEnabled) {
            mapViewController.setRotationEnabled(true)
            Logger.d(TAG, "Manual rotation restored")
        }

        // Effacer l'état sauvegardé
        preEditState = null

        Logger.i(TAG, "Edit mode exited")
    }

    private fun getCurrentDarkMode(): Boolean {
        val sharedPrefs = getSharedPreferences("map_settings", Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean("is_dark_mode", false)
    }

}
package com.brewdog.catamap.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.brewdog.catamap.R
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.data.repository.MapRepository
import com.brewdog.catamap.domain.compass.CompassManager
import com.brewdog.catamap.domain.map.MapLoader
import com.brewdog.catamap.domain.map.MapViewController
import com.brewdog.catamap.domain.minimap.MinimapController
import com.brewdog.catamap.ui.adapters.AccessibleSubsamplingImageView
import com.brewdog.catamap.ui.adapters.MinimapView
import com.brewdog.catamap.ui.views.RotationGestureDetector
import com.brewdog.catamap.utils.logging.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

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
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Repository
    private lateinit var repository: MapRepository

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

        Logger.i(TAG, "Repository initialized")
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
                // Appelé pendant zoom (pinch, double-tap, etc.)
                if (minimapEnabled) {
                    minimapController.updateViewport()
                }
            }

            override fun onCenterChanged(newCenter: PointF?, origin: Int) {
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
        val currentMap = mapViewController.getCurrentMap()

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
        }

        Logger.exit(TAG, "handleMenuItemClick")
    }

    /**
     * Charge la carte initiale
     */
    private fun loadInitialMap() {
        Logger.entry(TAG, "loadInitialMap")

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

        try {
            compassManager.unregister()
            mapViewController.cleanup()
            minimapController.cleanup()
            mapLoader.reset()
        } catch (e: Exception) {
            Logger.e(TAG, "Error during cleanup", e)
        }

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
                mapLoader.startLoading(map)
                mapViewController.loadMap(map, mapViewController.isDarkModeEnabled())
            }
        }

        Logger.exit(TAG, "onNewIntent")
    }

    private fun setupMinimapSize() {
        val screenWidth = resources.displayMetrics.widthPixels
        val minimapSize = (screenWidth * MinimapView.SCREEN_PERCENT).toInt()
        val params = minimapView.layoutParams as FrameLayout.LayoutParams
        params.width = minimapSize
        params.height = minimapSize
        minimapView.layoutParams = params
    }

}
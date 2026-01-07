package com.brewdog.catamap

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var azimuthFiltered = 0f
    private lateinit var compassView: ImageView
    private var rotateWithCompass = false
    lateinit var mapViewLight: AccessibleSubsamplingImageView
    lateinit var mapViewDark: AccessibleSubsamplingImageView
    lateinit var mapView: AccessibleSubsamplingImageView
    private var isUserInteracting = false
    private var darkModeEnabled = true
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastMapRotationTime = 0L
    private val mapRotationIntervalMs = 20L
    private var lastSensorUpdateTime = 0L
    private var sensorUpdateIntervalMs = 50L
    private var batterySaverEnabled = false
    private lateinit var rotationDetector: RotationGestureDetector
    private var manualRotateEnabled = false

    // Gestion des cartes
    private lateinit var storage: MapStorage
    private lateinit var database: MapDatabase
    private var currentMap: MapItem? = null
    private var isLoadingMap = false
    private val adjustedMaps = mutableSetOf<SubsamplingScaleImageView>()
    private lateinit var containerFrame: FrameLayout

    companion object {
        const val EXTRA_SELECTED_MAP_ID = "selected_map_id"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = MapStorage(this)
        database = storage.load()

        containerFrame = findViewById(android.R.id.content)
        compassView = findViewById(R.id.compassView)
        mapViewLight = findViewById(R.id.mapViewLight)
        mapViewDark = findViewById(R.id.mapViewDark)
        // Gérer les insets système (status bar, notch, etc.)
        setupWindowInsets()

        setupMapView(mapViewLight)
        setupMapView(mapViewDark)

        val selectedMapId = intent.getStringExtra(EXTRA_SELECTED_MAP_ID)
        currentMap = if (selectedMapId != null) {
            database.maps.find { it.id == selectedMapId }
        } else {
            database.maps.firstOrNull { it.isDefault }
        }

        if (currentMap == null) {
            currentMap = database.maps.firstOrNull()
        }

        loadCurrentMap()

        mapView.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                isUserInteracting = true
            }
            override fun onScaleChanged(newScale: Float, origin: Int) {
                isUserInteracting = true
            }
        })

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupMenuButton()

        rotationDetector = RotationGestureDetector { angle ->
            if (!rotateWithCompass && manualRotateEnabled) {
                mapView.rotation += angle
            }
        }

        setupRotationTouch(mapViewDark)
        setupRotationTouch(mapViewLight)
    }

    /**
     * Configure les marges dynamiques pour la status bar et les notchs
     */
    private fun setupWindowInsets() {
        val rootContainer = findViewById<FrameLayout>(R.id.rootContainer)
        val menuButton = findViewById<ImageButton>(R.id.menuButton)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val statusBarHeight = systemBars.top

            // Convertir 8dp en pixels
            val marginDp = -8
            val marginPx = (marginDp * resources.displayMetrics.density).toInt()

            // Appliquer les marges au bouton menu
            (menuButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = statusBarHeight + marginPx
            }
            menuButton.requestLayout()

            // Appliquer les marges à la boussole
            (compassView.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = statusBarHeight + marginPx
            }
            compassView.requestLayout()

            insets
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val selectedMapId = intent.getStringExtra(EXTRA_SELECTED_MAP_ID)
        if (selectedMapId != null) {
            database = storage.load()
            val newMap = database.maps.find { it.id == selectedMapId }
            if (newMap != null && newMap.id != currentMap?.id) {
                currentMap = newMap
                loadCurrentMap()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        database = storage.load()

        if (currentMap != null && database.maps.none { it.id == currentMap!!.id }) {
            currentMap = database.maps.firstOrNull { it.isDefault }
            loadCurrentMap()
        }

        updateSensors()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun loadCurrentMap() {
        if (isLoadingMap) return
        isLoadingMap = true

        currentMap?.let { map ->
            adjustedMaps.clear()
            mapViewLight.recycle()
            mapViewDark.recycle()

            resetViewTransformations(mapViewLight)
            resetViewTransformations(mapViewDark)

            if (map.isBuiltIn) {
                mapViewLight.setImage(ImageSource.resource(R.drawable.exemple_2025_light))
                mapViewDark.setImage(ImageSource.resource(R.drawable.exemple_2025_dark))

                setupMapListenerForNewMap(mapViewLight)
                setupMapListenerForNewMap(mapViewDark)

                if (darkModeEnabled) {
                    mapViewDark.visibility = View.VISIBLE
                    mapViewLight.visibility = View.GONE
                    mapView = mapViewDark
                } else {
                    mapViewLight.visibility = View.VISIBLE
                    mapViewDark.visibility = View.GONE
                    mapView = mapViewLight
                }
            } else {
                if (map.hasLightMode && map.hasDarkMode) {
                    mapViewLight.setImage(ImageSource.uri(map.lightImageUri!!))
                    mapViewDark.setImage(ImageSource.uri(map.darkImageUri!!))

                    setupMapListenerForNewMap(mapViewLight)
                    setupMapListenerForNewMap(mapViewDark)

                    if (darkModeEnabled) {
                        mapViewDark.visibility = View.VISIBLE
                        mapViewLight.visibility = View.GONE
                        mapView = mapViewDark
                    } else {
                        mapViewLight.visibility = View.VISIBLE
                        mapViewDark.visibility = View.GONE
                        mapView = mapViewLight
                    }
                } else {
                    val uri = if (darkModeEnabled && map.hasDarkMode) {
                        map.darkImageUri
                    } else if (!darkModeEnabled && map.hasLightMode) {
                        map.lightImageUri
                    } else {
                        map.darkImageUri ?: map.lightImageUri
                    }

                    if (uri != null) {
                        mapViewDark.setImage(ImageSource.uri(uri))
                        setupMapListenerForNewMap(mapViewDark)

                        mapViewDark.visibility = View.VISIBLE
                        mapViewLight.visibility = View.GONE
                        mapView = mapViewDark
                    }
                }
            }

            resetMapRotation()
        }

        mapView.postDelayed({ isLoadingMap = false }, 300)
    }

    private fun resetViewTransformations(view: SubsamplingScaleImageView) {
        view.rotation = 0f
        view.translationX = 0f
        view.translationY = 0f
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        view.requestLayout()
    }

    private fun setupMapListenerForNewMap(map: SubsamplingScaleImageView) {
        map.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                map.post {
                    adjustMapForRotation(map)
                    map.resetScaleAndCenter()
                }
            }
            override fun onImageLoaded() {}
            override fun onPreviewLoadError(e: Exception?) {}
            override fun onTileLoadError(e: Exception?) {}
            override fun onPreviewReleased() {}
            override fun onImageLoadError(e: Exception?) {}
        })
    }

    private fun setupMapListenerForModeSwitch(map: SubsamplingScaleImageView, oldScale: Float, oldCenter: PointF?, oldRotation: Float) {
        map.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                map.post {
                    if (map !in adjustedMaps) {
                        adjustMapForRotation(map)
                    }

                    map.postDelayed({
                        if (oldCenter != null) {
                            map.setScaleAndCenter(oldScale, oldCenter)
                            map.rotation = oldRotation
                        }
                    }, 50)
                }
            }
            override fun onImageLoaded() {}
            override fun onPreviewLoadError(e: Exception?) {}
            override fun onTileLoadError(e: Exception?) {}
            override fun onPreviewReleased() {}
            override fun onImageLoadError(e: Exception?) {}
        })
    }

    private fun adjustMapForRotation(map: SubsamplingScaleImageView) {
        if (map.width == 0 || map.height == 0) {
            map.postDelayed({ adjustMapForRotation(map) }, 50)
            return
        }

        if (map in adjustedMaps) return

        val screenWidth = containerFrame.width
        val screenHeight = containerFrame.height
        val screenDiagonal = kotlin.math.hypot(screenWidth.toFloat(), screenHeight.toFloat())

        val imageWidth = map.width
        val imageHeight = map.height

        val paddingNeeded = ((screenDiagonal - minOf(imageWidth, imageHeight)) / 2f * 1.1f).toInt()

        val params = FrameLayout.LayoutParams(
            imageWidth + paddingNeeded * 2,
            imageHeight + paddingNeeded * 2
        )
        map.layoutParams = params
        map.requestLayout()

        map.translationX = -paddingNeeded.toFloat()
        map.translationY = -paddingNeeded.toFloat()

        map.post {
            map.pivotX = (imageWidth + paddingNeeded * 2) / 2f
            map.pivotY = (imageHeight + paddingNeeded * 2) / 2f
        }

        adjustedMaps.add(map)
    }

    private fun setupRotationTouch(map: AccessibleSubsamplingImageView) {
        map.setOnTouchListener { v, event ->
            rotationDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            false
        }
    }

    /**
     * Configuration par défaut de SubsamplingScaleImageView
     */
    private fun setupMapView(map: SubsamplingScaleImageView) {
        map.isPanEnabled = true
        map.isZoomEnabled = true
        map.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
        map.setMaxTileSize(4096)
        map.setMinimumTileDpi(320)
        map.setExecutor(Executors.newFixedThreadPool(4))
    }

    private fun setMapDarkMode(enabled: Boolean) {
        if (enabled == darkModeEnabled || isLoadingMap) return
        isLoadingMap = true
        darkModeEnabled = enabled

        val oldScale = mapView.scale
        val oldCenter = mapView.center?.let { PointF(it.x, it.y) }
        val oldRotation = mapView.rotation

        val newMapView = if (enabled) mapViewDark else mapViewLight

        val shouldSwitchView = if (currentMap?.isBuiltIn == true) {
            true
        } else {
            currentMap?.hasLightMode == true && currentMap?.hasDarkMode == true
        }

        if (shouldSwitchView) {
            mapViewLight.visibility = if (enabled) View.GONE else View.VISIBLE
            mapViewDark.visibility = if (enabled) View.VISIBLE else View.GONE
            mapView = newMapView

            if (newMapView.isReady && newMapView in adjustedMaps) {
                newMapView.post {
                    val padding = if (newMapView.width > 0 && newMapView.height > 0) {
                        val screenWidth = containerFrame.width
                        val screenHeight = containerFrame.height
                        val screenDiagonal = kotlin.math.hypot(screenWidth.toFloat(), screenHeight.toFloat())
                        val imageWidth = newMapView.width
                        val imageHeight = newMapView.height
                        ((screenDiagonal - minOf(imageWidth, imageHeight)) / 2f * 1.1f).toInt()
                    } else {
                        0
                    }

                    if (padding > 0) {
                        newMapView.pivotX = (newMapView.width + padding * 2) / 2f
                        newMapView.pivotY = (newMapView.height + padding * 2) / 2f
                    }

                    newMapView.postDelayed({
                        if (oldCenter != null) {
                            newMapView.setScaleAndCenter(oldScale, oldCenter)
                            newMapView.rotation = oldRotation
                        }
                        isLoadingMap = false
                    }, 50)
                }
            } else {
                setupMapListenerForModeSwitch(newMapView, oldScale, oldCenter, oldRotation)
                isLoadingMap = false
            }
        } else {
            isLoadingMap = false
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastSensorUpdateTime < sensorUpdateIntervalMs) return
        lastSensorUpdateTime = now

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
        }

        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) return

        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        azimuthFiltered = smoothAngle(azimuthDeg, azimuthFiltered)

        compassView.rotation = -azimuthFiltered

        if (rotateWithCompass) rotateMapTo(-azimuthFiltered)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateSensors() {
        sensorManager.unregisterListener(this)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun smoothAngle(target: Float, current: Float): Float {
        var delta = target - current
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        val alpha = if (kotlin.math.abs(delta) < 5f) 0.03f else 0.08f
        return current + delta * alpha
    }

    private fun angleDifference(target: Float, current: Float): Float {
        var diff = target - current
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    private fun setSensorsEnabled(enabled: Boolean) {
        sensorManager.unregisterListener(this)
        if (!enabled) return

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun updateRotationAndCompass() {
        if (batterySaverEnabled) {
            rotateWithCompass = false
            compassView.visibility = View.GONE
            resetMapRotation()
            setSensorsEnabled(false)
        } else {
            compassView.visibility = View.VISIBLE
            setSensorsEnabled(true)

            if (!rotateWithCompass) {
                resetMapRotation()
            }
        }
    }

    private fun setupMenuButton() {
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        menuButton.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            popup.menu.findItem(R.id.action_rotate_with_compass).isChecked = rotateWithCompass
            popup.menu.findItem(R.id.action_dark_mode).isChecked = darkModeEnabled
            popup.menu.findItem(R.id.action_battery_saver).isChecked = batterySaverEnabled
            popup.menu.findItem(R.id.action_manual_rotate).isChecked = manualRotateEnabled
            popup.menu.findItem(R.id.action_reset_rotation).isVisible = manualRotateEnabled

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rotate_with_compass -> {
                        rotateWithCompass = !item.isChecked
                        item.isChecked = rotateWithCompass

                        if (rotateWithCompass && manualRotateEnabled) {
                            manualRotateEnabled = false
                            popup.menu.findItem(R.id.action_manual_rotate).isChecked = false
                        }

                        updateRotationAndCompass()
                        true
                    }

                    R.id.action_dark_mode -> {
                        val newValue = !item.isChecked
                        item.isChecked = newValue
                        setMapDarkMode(newValue)
                        true
                    }

                    R.id.action_manual_rotate -> {
                        val wasEnabled = manualRotateEnabled
                        manualRotateEnabled = !item.isChecked
                        item.isChecked = manualRotateEnabled

                        if (manualRotateEnabled && rotateWithCompass) {
                            rotateWithCompass = false
                            popup.menu.findItem(R.id.action_rotate_with_compass).isChecked = false
                        }

                        if (wasEnabled && !manualRotateEnabled) {
                            resetMapRotation()
                        }

                        true
                    }

                    R.id.action_battery_saver -> {
                        batterySaverEnabled = !item.isChecked
                        item.isChecked = batterySaverEnabled

                        if (batterySaverEnabled && rotateWithCompass) {
                            rotateWithCompass = false
                            popup.menu.findItem(R.id.action_rotate_with_compass).isChecked = false
                        }

                        updateRotationAndCompass()
                        true
                    }

                    R.id.action_reset_rotation -> {
                        if (manualRotateEnabled) {
                            resetMapRotation()
                        }
                        true
                    }

                    R.id.action_manage_maps -> {
                        startActivity(Intent(this, MapManagerActivity::class.java))
                        true
                    }

                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun rotateMapTo(targetRotation: Float) {
        val now = System.currentTimeMillis()
        if (now - lastMapRotationTime < mapRotationIntervalMs) return
        lastMapRotationTime = now

        val diff = angleDifference(targetRotation, mapView.rotation)
        if (kotlin.math.abs(diff) < 0.5f) return

        mapView.rotation += diff * 0.9f

        if (isUserInteracting) {
            mapView.postDelayed({ isUserInteracting = false }, 50)
        }
    }

    private fun resetMapRotation() {
        if (::mapView.isInitialized) {
            mapView.rotation = 0f
        }
        compassView.rotation = 0f
    }
}
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
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var azimuthFiltered = 0f
    private lateinit var compassView: ImageView
    private var rotateWithCompass = false
    private lateinit var mapView: AccessibleSubsamplingImageView
    private val mapState = MapState()
    private lateinit var loadingOverlay: FrameLayout
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
    private lateinit var storage: MapStorage
    private lateinit var database: MapDatabase
    private var currentMap: MapItem? = null
    private var isLoadingMap = false
    private var isMapAdjusted = false
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

        mapView = findViewById(R.id.mapView)

        loadingOverlay = findViewById(R.id.loadingOverlay)

        setupWindowInsets()
        setupMapView(mapView)

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

        setupRotationTouch(mapView)
    }

    private fun setupWindowInsets() {
        val rootContainer = findViewById<FrameLayout>(R.id.rootContainer)
        val menuButton = findViewById<ImageButton>(R.id.menuButton)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val statusBarHeight = systemBars.top
            val marginDp = -8
            val marginPx = (marginDp * resources.displayMetrics.density).toInt()

            (menuButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = statusBarHeight + marginPx
            }
            menuButton.requestLayout()

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

        val currentMapId = currentMap?.id
        val updatedMap = database.maps.find { it.id == currentMapId }

        if (updatedMap != null && updatedMap != currentMap) {
            currentMap = updatedMap
            loadCurrentMap()
        }

        if (!batterySaverEnabled) {
            setSensorsEnabled(true)
        }
    }

    private fun setSensorsEnabled(enabled: Boolean) {
        if (enabled) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magnetometer ->
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
            }
        } else {
            sensorManager.unregisterListener(this)
        }
    }

    private fun setupMenuButton() {
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        menuButton.setOnClickListener {
            showMenu()
        }
    }

    private fun showMenu() {
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        val popup = PopupMenu(this, menuButton)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        popup.menu.findItem(R.id.menu_dark_mode)?.isChecked = darkModeEnabled
        popup.menu.findItem(R.id.menu_rotate_compass)?.isChecked = rotateWithCompass
        popup.menu.findItem(R.id.menu_rotate_manual)?.isChecked = manualRotateEnabled
        popup.menu.findItem(R.id.menu_battery_saver)?.isChecked = batterySaverEnabled

        popup.menu.findItem(R.id.menu_reset_rotation)?.isVisible = manualRotateEnabled

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_map_list -> {
                    val intent = Intent(this, MapManagerActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.menu_dark_mode -> {
                    setMapDarkMode(!darkModeEnabled)
                    true
                }
                R.id.menu_rotate_compass -> {
                    rotateWithCompass = !rotateWithCompass
                    item.isChecked = rotateWithCompass
                    if (rotateWithCompass) {
                        manualRotateEnabled = false
                        resetMapRotation()
                    } else {
                        resetMapRotation()
                    }
                    updateRotationAndCompass()
                    true
                }
                R.id.menu_rotate_manual -> {
                    manualRotateEnabled = !manualRotateEnabled
                    item.isChecked = manualRotateEnabled
                    if (manualRotateEnabled) {
                        rotateWithCompass = false
                    } else {
                        resetMapRotation()
                    }
                    true
                }
                R.id.menu_battery_saver -> {
                    batterySaverEnabled = !batterySaverEnabled
                    item.isChecked = batterySaverEnabled
                    updateRotationAndCompass()
                    true
                }
                R.id.menu_reset_rotation -> {
                    resetMapRotation()
                    true
                }
                else -> false
            }
        }
        popup.show()
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
        }
    }

    private fun calculateMinScaleWithPadding(map: SubsamplingScaleImageView): Float {
        android.util.Log.d("MainActivity", "calculateMinScaleWithPadding appelée")

        val screenWidth = containerFrame.width
        val screenHeight = containerFrame.height
        val screenDiagonal = kotlin.math.hypot(screenWidth.toFloat(), screenHeight.toFloat())

        val sourceImageWidth = map.sWidth.toFloat()
        val sourceImageHeight = map.sHeight.toFloat()

        android.util.Log.d("MainActivity", "Écran: ${screenWidth}×${screenHeight}, Image: ${sourceImageWidth}×${sourceImageHeight}")

        val minScreenDimension = minOf(screenWidth, screenHeight).toFloat()

        val paddingNeeded = ((screenDiagonal - minScreenDimension) / 2f * 1.1f).toInt()

        android.util.Log.d("MainActivity", "Diagonal: $screenDiagonal, Padding estimé: $paddingNeeded")

        val estimatedViewWidth = screenWidth + paddingNeeded * 2

        val paddingRatio = screenWidth.toFloat() / estimatedViewWidth.toFloat()

        val baseMinScaleWidth = screenWidth.toFloat() / sourceImageWidth
        val baseMinScaleHeight = screenHeight.toFloat() / sourceImageHeight
        val baseMinScale = minOf(baseMinScaleWidth, baseMinScaleHeight)

        val adjustmentFactor = 2.5f

        val correctedMinScale = baseMinScale * paddingRatio * adjustmentFactor

        android.util.Log.d("MainActivity", "minScale calculé: $correctedMinScale (base=$baseMinScale, ratio=$paddingRatio, ajustement=$adjustmentFactor, padding=$paddingNeeded)")

        return correctedMinScale
    }

    private fun resetMapRotation() {
        mapView.rotation = 0f
        compassView.rotation = 0f
    }

    private fun loadCurrentMap() {
        if (isLoadingMap) return
        isLoadingMap = true

        showLoader()

        currentMap?.let { map ->
            isMapAdjusted = false
            mapView.recycle()
            resetViewTransformations(mapView)

            val backgroundColor = if (darkModeEnabled) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            mapView.setBackgroundColor(backgroundColor)

            val imageSource = when {
                map.isBuiltIn -> {
                    val drawableId = if (darkModeEnabled) {
                        R.drawable.exemple_2025_dark
                    } else {
                        R.drawable.exemple_2025_light
                    }
                    ImageSource.resource(drawableId)
                }
                darkModeEnabled && map.hasDarkMode -> {
                    ImageSource.uri(map.darkImageUri!!)
                }
                !darkModeEnabled && map.hasLightMode -> {
                    ImageSource.uri(map.lightImageUri!!)
                }
                map.hasDarkMode -> {
                    ImageSource.uri(map.darkImageUri!!)
                }
                map.hasLightMode -> {
                    ImageSource.uri(map.lightImageUri!!)
                }
                else -> {
                    isLoadingMap = false
                    hideLoader()
                    return
                }
            }

            mapView.setImage(imageSource)
            setupMapListenerForNewMap(mapView)
            resetMapRotation()
        }

        isLoadingMap = false
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
                    val calculatedMinScale = calculateMinScaleWithPadding(map)
                    map.minScale = calculatedMinScale
                    map.maxScale = 2.0f

                    android.util.Log.d("MainActivity", "minScale appliqué: $calculatedMinScale")

                    if (map.isVisible && !isMapAdjusted) {
                        adjustMapForRotation(map)

                        map.postDelayed({
                            val center = android.graphics.PointF(
                                map.sWidth / 2f,
                                map.sHeight / 2f
                            )
                            map.setScaleAndCenter(calculatedMinScale, center)
                            android.util.Log.d("MainActivity", "Carte centrée à $center avec scale $calculatedMinScale")

                            hideLoader()
                        }, 50)
                    } else {
                        hideLoader()
                    }
                }
            }
            override fun onImageLoaded() {}
            override fun onPreviewLoadError(e: Exception?) {
                hideLoader()
            }
            override fun onTileLoadError(e: Exception?) {}
            override fun onPreviewReleased() {}
            override fun onImageLoadError(e: Exception?) {
                hideLoader()
            }
        })
    }

    private fun adjustMapForRotation(map: SubsamplingScaleImageView) {
        if (map.width == 0 || map.height == 0) {
            map.postDelayed({ adjustMapForRotation(map) }, 50)
            return
        }

        if (isMapAdjusted) {
            return
        }

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

        isMapAdjusted = true
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

    private fun setupMapView(map: SubsamplingScaleImageView) {
        map.isPanEnabled = true
        map.isZoomEnabled = true
        map.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
        map.setMaxTileSize(4096)
        map.setMinimumTileDpi(320)
        map.setExecutor(Executors.newFixedThreadPool(4))

        map.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
    }

    private fun showLoader() {
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.alpha = 1f
    }

    private fun hideLoader() {
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
            }
            .start()
    }

    private fun setMapDarkMode(enabled: Boolean) {
        if (enabled == darkModeEnabled || isLoadingMap) return
        isLoadingMap = true

        showLoader()

        val currentMapItem = currentMap ?: run {
            isLoadingMap = false
            hideLoader()
            return
        }

        mapView.post {
            mapState.capture(mapView)

            android.util.Log.d("MainActivity", "Changement de mode: ${if (enabled) "Light→Dark" else "Dark→Light"}")

            val backgroundColor = if (enabled) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            mapView.setBackgroundColor(backgroundColor)

            val newImageSource = when {
                currentMapItem.isBuiltIn -> {
                    val drawableId = if (enabled) {
                        R.drawable.exemple_2025_dark
                    } else {
                        R.drawable.exemple_2025_light
                    }
                    ImageSource.resource(drawableId)
                }
                enabled && currentMapItem.hasDarkMode -> {
                    ImageSource.uri(currentMapItem.darkImageUri!!)
                }
                !enabled && currentMapItem.hasLightMode -> {
                    ImageSource.uri(currentMapItem.lightImageUri!!)
                }
                currentMapItem.hasDarkMode -> {
                    ImageSource.uri(currentMapItem.darkImageUri!!)
                }
                currentMapItem.hasLightMode -> {
                    ImageSource.uri(currentMapItem.lightImageUri!!)
                }
                else -> {
                    android.util.Log.e("MainActivity", "Pas d'image disponible pour ce mode")
                    isLoadingMap = false
                    hideLoader()
                    return@post
                }
            }

            darkModeEnabled = enabled

            mapView.recycle()
            mapView.setImage(newImageSource)

            mapView.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    mapView.post {
                        val calculatedMinScale = calculateMinScaleWithPadding(mapView)
                        mapView.minScale = calculatedMinScale
                        mapView.maxScale = 2.0f

                        if (!isMapAdjusted) {
                            adjustMapForRotation(mapView)
                        }

                        mapView.postDelayed({
                            mapState.apply(mapView)
                            isLoadingMap = false

                            hideLoader()

                            android.util.Log.d("MainActivity", "Mode ${if (enabled) "Dark" else "Light"} appliqué avec état préservé")
                        }, 10)
                    }
                }

                override fun onImageLoaded() {}
                override fun onPreviewLoadError(e: Exception?) {
                    android.util.Log.e("MainActivity", "Erreur chargement preview", e)
                    isLoadingMap = false
                    hideLoader()
                }
                override fun onImageLoadError(e: Exception?) {
                    android.util.Log.e("MainActivity", "Erreur chargement image", e)
                    isLoadingMap = false
                    hideLoader()
                }
                override fun onTileLoadError(e: Exception?) {}
                override fun onPreviewReleased() {}
            })
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
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

        if (rotateWithCompass && now - lastMapRotationTime >= mapRotationIntervalMs) {
            lastMapRotationTime = now
            rotateMapTo(-azimuthFiltered)
        }
    }

    private fun smoothAngle(target: Float, current: Float): Float {
        var delta = target - current
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        val alpha = if (kotlin.math.abs(delta) < 5f) 0.03f else 0.08f
        return current + delta * alpha
    }

    private fun rotateMapTo(angle: Float) {
        mapView.rotation = angle
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
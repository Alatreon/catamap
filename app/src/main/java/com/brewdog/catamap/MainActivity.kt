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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.view.isVisible
import java.util.concurrent.Executor


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
    private var mapExecutor: ExecutorService? = null
    private var adjustMapRetryCount = 0
    private val maxAdjustRetries = 20

    private val imageEventListener = object : SubsamplingScaleImageView.OnImageEventListener {
        override fun onReady() {
            android.util.Log.d("MainActivity", "imageEventListener.onReady: début, isMapAdjusted=${isMapAdjusted}")
            mapView.post {
                val calculatedMinScale = calculateMinScaleWithPadding(mapView)
                mapView.minScale = calculatedMinScale
                mapView.maxScale = 2.0f

                if (!isMapAdjusted) {
                    android.util.Log.d("MainActivity", "imageEventListener: appel adjustMapForRotation")
                    adjustMapForRotation(mapView)
                } else {
                    android.util.Log.d("MainActivity", "imageEventListener: skip adjustMapForRotation car déjà ajusté")
                }

                mapView.postDelayed({
                    mapState.apply(mapView)
                    isLoadingMap = false

                    hideLoader()

                    android.util.Log.d("MainActivity", "Image prête avec état préservé")
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
    }

    // Listener spécifique pour le chargement initial d'une nouvelle carte
    private val initialMapLoadListener = object : SubsamplingScaleImageView.OnImageEventListener {
        override fun onReady() {
            mapView.post {
                val calculatedMinScale = calculateMinScaleWithPadding(mapView)
                mapView.minScale = calculatedMinScale
                mapView.maxScale = 2.0f

                android.util.Log.d("MainActivity", "minScale appliqué: $calculatedMinScale")

                if (mapView.isVisible && !isMapAdjusted) {
                    adjustMapForRotation(mapView)

                    mapView.postDelayed({
                        val center = PointF(
                            mapView.sWidth / 2f,
                            mapView.sHeight / 2f
                        )
                        mapView.setScaleAndCenter(calculatedMinScale, center)
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
    }

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

    private fun resetMapRotation() {
        mapView.rotation = 0f
        compassView.rotation = 0f
    }

    private fun loadCurrentMap() {
        if (isLoadingMap) return
        isLoadingMap = true

        try {
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
                        val uri = map.darkImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "Dark mode activé mais darkImageUri est null")
                            hideLoader()
                            return
                        }
                    }
                    !darkModeEnabled && map.hasLightMode -> {
                        val uri = map.lightImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "Light mode activé mais lightImageUri est null")
                            hideLoader()
                            return
                        }
                    }
                    map.hasDarkMode -> {
                        val uri = map.darkImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "hasDarkMode=true mais darkImageUri est null")
                            hideLoader()
                            return
                        }
                    }
                    map.hasLightMode -> {
                        val uri = map.lightImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "Carte a hasLightMode mais lightImageUri est null")
                            hideLoader()
                            return
                        }
                    }
                    else -> {
                        hideLoader()
                        return
                    }
                }

                mapView.setImage(imageSource)
                mapView.setOnImageEventListener(initialMapLoadListener)
                resetMapRotation()
            }
        } finally {
            isLoadingMap = false
        }
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
    private fun adjustMapForRotation(map: SubsamplingScaleImageView) {
        if (map.width == 0 || map.height == 0) {
            if (adjustMapRetryCount >= maxAdjustRetries) {
                android.util.Log.e("MainActivity", "adjustMapForRotation: Échec après $maxAdjustRetries tentatives")
                adjustMapRetryCount = 0
                return
            }
            adjustMapRetryCount++
            android.util.Log.d("MainActivity", "adjustMapForRotation: retry $adjustMapRetryCount/$maxAdjustRetries")
            map.postDelayed({ adjustMapForRotation(map) }, 50)
            return
        }

        adjustMapRetryCount = 0  // Reset du compteur après succès

        if (isMapAdjusted) {
            android.util.Log.d("MainActivity", "adjustMapForRotation: déjà ajusté, skip")
            return
        }

        val screenWidth = containerFrame.width
        val screenHeight = containerFrame.height
        val screenDiagonal = kotlin.math.hypot(screenWidth.toFloat(), screenHeight.toFloat())

        val imageWidth = map.width
        val imageHeight = map.height

        val paddingNeeded = ((screenDiagonal - minOf(imageWidth, imageHeight)) / 2f * 1.1f).toInt()

        android.util.Log.d("MainActivity", "adjustMapForRotation: écran=${screenWidth}x${screenHeight}, image=${imageWidth}x${imageHeight}, padding=${paddingNeeded}")

        val params = FrameLayout.LayoutParams(
            imageWidth + paddingNeeded * 2,
            imageHeight + paddingNeeded * 2
        )
        map.layoutParams = params
        map.requestLayout()

        map.translationX = -paddingNeeded.toFloat()
        map.translationY = -paddingNeeded.toFloat()

        map.post {
            val pivotX = (imageWidth + paddingNeeded * 2) / 2f
            val pivotY = (imageHeight + paddingNeeded * 2) / 2f
            map.pivotX = pivotX
            map.pivotY = pivotY
            android.util.Log.d("MainActivity", "adjustMapForRotation: pivot défini à (${pivotX}, ${pivotY})")
        }

        isMapAdjusted = true
        android.util.Log.d("MainActivity", "adjustMapForRotation: TERMINE, isMapAdjusted=true")
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
        // Configuration de base
        map.apply {
            isPanEnabled = true
            isZoomEnabled = true
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
            setTileBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        setupMapExecutor(map)
    }

    /**
     * Configure le pool de threads optimal pour le décodage des tuiles
     */
    private fun setupMapExecutor(map: SubsamplingScaleImageView) {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val threadCount = minOf(cpuCount - 1, 4).coerceAtLeast(2)

        android.util.Log.d("MainActivity", "CPU cores: $cpuCount → Using $threadCount decoder threads")

        mapExecutor = Executors.newFixedThreadPool(threadCount) { runnable ->
            Thread(runnable).apply {
                priority = Thread.NORM_PRIORITY + 1
            }
        }

        map.setExecutor(mapExecutor as Executor)
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
        android.util.Log.d("MainActivity", "setMapDarkMode: début, enabled=${enabled}, darkModeEnabled=${darkModeEnabled}, isLoadingMap=${isLoadingMap}")

        // Protection renforcée avec synchronisation
        synchronized(this) {
            if (enabled == darkModeEnabled || isLoadingMap) return
            isLoadingMap = true
        }

        showLoader()

        val currentMapItem = currentMap ?: run {
            synchronized(this) { isLoadingMap = false }  // ← CORRECTION : synchronized
            hideLoader()
            return
        }

        mapView.post {
            try {  // ← AJOUT : try-catch pour garantir le cleanup
                android.util.Log.d("MainActivity", "setMapDarkMode: dans post, avant isMapAdjusted=false")
                isMapAdjusted = false
                mapState.capture(mapView)

                android.util.Log.d("MainActivity", "Changement de mode: ${if (enabled) "Light→Dark" else "Dark→Light"}")

                resetViewTransformations(mapView)

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
                        val uri = currentMapItem.darkImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "Dark mode demandé mais darkImageUri est null")
                            synchronized(this) { isLoadingMap = false }  // ← CORRECTION
                            hideLoader()
                            return@post
                        }
                    }
                    !enabled && currentMapItem.hasLightMode -> {
                        val uri = currentMapItem.lightImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "Light mode demandé mais lightImageUri est null")
                            synchronized(this) { isLoadingMap = false }  // ← CORRECTION
                            hideLoader()
                            return@post
                        }
                    }
                    currentMapItem.hasDarkMode -> {
                        val uri = currentMapItem.darkImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "hasDarkMode=true mais darkImageUri est null")
                            synchronized(this) { isLoadingMap = false }  // ← CORRECTION
                            hideLoader()
                            return@post
                        }
                    }
                    currentMapItem.hasLightMode -> {
                        val uri = currentMapItem.lightImageUri
                        if (uri != null) {
                            ImageSource.uri(uri)
                        } else {
                            android.util.Log.e("MainActivity", "hasLightMode=true mais lightImageUri est null")
                            synchronized(this) { isLoadingMap = false }  // ← CORRECTION
                            hideLoader()
                            return@post
                        }
                    }
                    else -> {
                        android.util.Log.e("MainActivity", "Pas d'image disponible pour ce mode")
                        synchronized(this) { isLoadingMap = false }  // ← CORRECTION
                        hideLoader()
                        return@post
                    }
                }

                darkModeEnabled = enabled

                mapView.setImage(newImageSource)

                mapView.setOnImageEventListener(imageEventListener)

            } catch (e: Exception) {  // ← AJOUT : catch pour garantir le cleanup
                android.util.Log.e("MainActivity", "Erreur setMapDarkMode", e)
                synchronized(this) { isLoadingMap = false }
                hideLoader()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cleanup dans un try-catch pour éviter crash sur cleanup
        try {
            sensorManager.unregisterListener(this)
            mapExecutor?.shutdown()
            mapView.setOnImageEventListener(null)
            mapView.recycle()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Erreur cleanup", e)
        } finally {
            mapExecutor = null
        }
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
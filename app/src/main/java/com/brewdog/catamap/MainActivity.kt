package com.brewdog.catamap

import android.annotation.SuppressLint
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

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
    private val adjustedMaps = mutableSetOf<SubsamplingScaleImageView>()
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastMapRotationTime = 0L
    private val mapRotationIntervalMs = 20L
    private var lastSensorUpdateTime = 0L
    private var sensorUpdateIntervalMs = 50L
    private var batterySaverEnabled = false
    private lateinit var rotationDetector: RotationGestureDetector
    private var manualRotateEnabled = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        compassView = findViewById(R.id.compassView)
        mapViewLight = findViewById(R.id.mapViewLight)
        mapViewDark = findViewById(R.id.mapViewDark)

        setupMapView(mapViewLight)
        setupMapView(mapViewDark)

        mapViewLight.setImage(ImageSource.resource(R.drawable.fdp_2019_light))
        mapViewDark.setImage(ImageSource.resource(R.drawable.fdp_2019_dark))
        mapView = mapViewDark

        setupMapReadyListener(mapViewDark)
        setupMapReadyListener(mapViewLight)

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

    private fun setupMapReadyListener(map: SubsamplingScaleImageView) {
        map.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() {
                adjustMapForRotation(map)
            }

            override fun onImageLoaded() {}
            override fun onPreviewLoadError(e: Exception?) {}
            override fun onTileLoadError(e: Exception?) {}
            override fun onPreviewReleased() {}
            override fun onImageLoadError(e: Exception?) {}
        })
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
    }

    private fun adjustMapForRotation(map: SubsamplingScaleImageView) {
        if (map in adjustedMaps || map.width == 0 || map.height == 0) return

        val padding = requiredRotationPaddingPx(map.width, map.height)

        map.layoutParams.width = map.width + padding * 2
        map.layoutParams.height = map.height + padding * 2
        map.requestLayout()

        map.pivotX = (map.width + padding * 2) / 2f
        map.pivotY = (map.height + padding * 2) / 2f
        map.translationX = -padding.toFloat()
        map.translationY = -padding.toFloat()

        adjustedMaps.add(map)
    }

    private fun setMapDarkMode(enabled: Boolean) {
        if (enabled == darkModeEnabled) return
        darkModeEnabled = enabled

        val oldScale = mapView.scale
        val oldCenter = mapView.center
        val oldRotation = mapView.rotation

        mapViewLight.visibility = if (enabled) View.GONE else View.VISIBLE
        mapViewDark.visibility = if (enabled) View.VISIBLE else View.GONE
        mapView = if (enabled) mapViewDark else mapViewLight

        mapView.post {
            adjustMapForRotation(mapView)
            oldCenter?.let { mapView.setScaleAndCenter(oldScale, it) }
            mapView.rotation = oldRotation
        }
    }

    override fun onResume() {
        super.onResume()
        updateSensors()
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

    private fun requiredRotationPaddingPx(width: Int, height: Int): Int {
        val diagonal = kotlin.math.hypot(width.toFloat(), height.toFloat())
        val padding = (diagonal - minOf(width, height)) / 2f
        return (padding * 1.05f).toInt()
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
            // Battery Saver activé : carte fixe + boussole cachée
            rotateWithCompass = false
            compassView.visibility = View.GONE
            resetMapRotation()
            setSensorsEnabled(false)
        } else {
            // Battery Saver désactivé : boussole visible et fonctionnelle
            compassView.visibility = View.VISIBLE
            setSensorsEnabled(true)

            if (!rotateWithCompass) {
                // Carte non rotative : recentrer carte au nord
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
        mapView.rotation = 0f
        compassView.rotation = 0f
    }
}
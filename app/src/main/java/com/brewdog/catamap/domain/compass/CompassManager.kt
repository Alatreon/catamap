package com.brewdog.catamap.domain.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.ImageView
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.utils.logging.Logger
import kotlin.math.abs

/**
 * Gestionnaire de la boussole et des capteurs
 * Responsabilité : Gérer l'orientation du device et la rotation de la carte
 */
class CompassManager(
    context: Context,
    private val compassView: ImageView,
    private val onRotationChanged: (Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "CompassManager"
    }

    private val sensorManager: SensorManager = 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val accelerometer: Sensor? = 
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val magnetometer: Sensor? = 
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // État des capteurs
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // Filtrage et lissage
    private var azimuthFiltered = 0f
    private var lastSensorUpdateTime = 0L
    private var lastMapRotationTime = 0L
    
    // Configuration
    private var sensorUpdateIntervalMs = AppConstants.Compass.SENSOR_UPDATE_INTERVAL_MS
    private var rotateWithCompass = false
    private var isRegistered = false

    init {
        Logger.i(TAG, "CompassManager initialized")
        logSensorAvailability()
    }

    /**
     * Log la disponibilité des capteurs
     */
    private fun logSensorAvailability() {
        Logger.state(TAG, "Sensors", mapOf(
            "accelerometer" to (accelerometer != null),
            "magnetometer" to (magnetometer != null)
        ))
        
        if (accelerometer == null) {
            Logger.e(TAG, "Accelerometer not available on this device")
        }
        if (magnetometer == null) {
            Logger.e(TAG, "Magnetometer not available on this device")
        }
    }

    /**
     * Active l'écoute des capteurs
     */
    fun register() {
        Logger.entry(TAG, "register")
        
        if (isRegistered) {
            Logger.w(TAG, "Sensors already registered")
            return
        }
        
        if (accelerometer == null || magnetometer == null) {
            Logger.e(TAG, "Cannot register: sensors not available")
            return
        }
        
        val accelRegistered = sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
        
        val magnetRegistered = sensorManager.registerListener(
            this,
            magnetometer,
            SensorManager.SENSOR_DELAY_UI
        )
        
        isRegistered = accelRegistered && magnetRegistered
        
        Logger.i(TAG, "Sensors registered: accel=$accelRegistered, magnet=$magnetRegistered")
        Logger.exit(TAG, "register")
    }

    /**
     * Désactive l'écoute des capteurs
     */
    fun unregister() {
        Logger.entry(TAG, "unregister")
        
        if (!isRegistered) {
            Logger.d(TAG, "Sensors not registered, nothing to do")
            return
        }
        
        sensorManager.unregisterListener(this)
        isRegistered = false
        
        Logger.i(TAG, "Sensors unregistered")
        Logger.exit(TAG, "unregister")
    }

    /**
     * Active/désactive la rotation automatique avec la boussole
     */
    fun setRotateWithCompass(enabled: Boolean) {
        Logger.entry(TAG, "setRotateWithCompass", enabled)
        
        if (rotateWithCompass == enabled) {
            Logger.d(TAG, "rotateWithCompass already $enabled")
            return
        }
        
        rotateWithCompass = enabled
        Logger.i(TAG, "Compass rotation ${if (enabled) "enabled" else "disabled"}")
        
        Logger.exit(TAG, "setRotateWithCompass")
    }

    /**
     * Vérifie si la rotation avec boussole est active
     */
    fun isRotateWithCompassEnabled(): Boolean = rotateWithCompass

    /**
     * Active le mode économie de batterie (réduit la fréquence des updates)
     */
    fun setBatterySaverEnabled(enabled: Boolean) {
        Logger.entry(TAG, "setBatterySaverEnabled", enabled)
        
        sensorUpdateIntervalMs = if (enabled) {
            AppConstants.UI.BATTERY_SAVER_SENSOR_INTERVAL_MS
        } else {
            AppConstants.Compass.SENSOR_UPDATE_INTERVAL_MS
        }
        
        Logger.i(TAG, "Battery saver ${if (enabled) "enabled" else "disabled"}, interval=${sensorUpdateIntervalMs}ms")
        Logger.exit(TAG, "setBatterySaverEnabled")
    }

    /**
     * Récupère l'azimuth actuel
     */
    fun getCurrentAzimuth(): Float = azimuthFiltered

    // ========== SensorEventListener ==========

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        
        // Throttle des updates
        if (now - lastSensorUpdateTime < sensorUpdateIntervalMs) {
            return
        }
        lastSensorUpdateTime = now

        // Copier les valeurs des capteurs
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                // Logger.v(TAG, "Accelerometer: x=${event.values[0]}, y=${event.values[1]}, z=${event.values[2]}")
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                // Logger.v(TAG, "Magnetometer: x=${event.values[0]}, y=${event.values[1]}, z=${event.values[2]}")
            }
        }

        // Calculer la matrice de rotation
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        
        if (!success) {
            Logger.v(TAG, "Failed to get rotation matrix")
            return
        }

        // Calculer l'orientation
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val azimuthRad = orientationAngles[0]
        val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()

        // Lisser l'angle
        azimuthFiltered = smoothAngle(azimuthDeg, azimuthFiltered)
        
        // Logger.v(TAG, "Azimuth: raw=$azimuthDeg°, filtered=$azimuthFiltered°")

        // Rotation de la vue boussole
        compassView.rotation = -azimuthFiltered

        // Rotation de la carte si activé
        if (rotateWithCompass && now - lastMapRotationTime >= AppConstants.Compass.MAP_ROTATION_INTERVAL_MS) {
            lastMapRotationTime = now
            Logger.v(TAG, "Rotating map to ${-azimuthFiltered}°")
            onRotationChanged(-azimuthFiltered)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val accuracyStr = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
        Logger.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracyStr")
    }

    /**
     * Lisse un angle pour éviter les sauts brusques
     */
    private fun smoothAngle(target: Float, current: Float): Float {
        var delta = target - current
        
        // Normaliser entre -180 et 180
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        // Appliquer un facteur de lissage adaptatif
        val alpha = if (abs(delta) < AppConstants.Compass.SMOOTH_ANGLE_THRESHOLD) {
            AppConstants.Compass.SMOOTH_ALPHA_SMALL
        } else {
            AppConstants.Compass.SMOOTH_ALPHA_LARGE
        }

        val smoothed = current + delta * alpha
        
        // Logger.v(TAG, "smoothAngle: target=$target, current=$current, delta=$delta, alpha=$alpha, result=$smoothed")
        
        return smoothed
    }

    /**
     * Reset l'état du compass
     */
    fun reset() {
        Logger.entry(TAG, "reset")
        azimuthFiltered = 0f
        lastSensorUpdateTime = 0L
        lastMapRotationTime = 0L
        Logger.i(TAG, "Compass state reset")
        Logger.exit(TAG, "reset")
    }

    /**
     * Log l'état actuel du compass manager
     */
    fun logState() {
        Logger.state(TAG, "CompassManager", mapOf(
            "isRegistered" to isRegistered,
            "rotateWithCompass" to rotateWithCompass,
            "azimuthFiltered" to azimuthFiltered,
            "sensorUpdateIntervalMs" to sensorUpdateIntervalMs
        ))
    }
}

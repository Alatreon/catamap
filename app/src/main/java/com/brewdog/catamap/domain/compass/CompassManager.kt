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
import kotlin.math.sign

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
    private var lastSentRotation: Float = 0f

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
        rotateWithCompass = enabled
        if (!enabled) {
            lastSentRotation = 0f
        }
        Logger.i(TAG, "Rotate with compass: $enabled")
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

    override fun onSensorChanged(event: SensorEvent) {
        // Copier les valeurs des capteurs (toujours, sans throttle)
        copySensorData(event)

        // Calculer l'azimuth
        val azimuth = calculateAzimuth() ?: return

        // Lisser et appliquer la rotation
        azimuthFiltered = smoothAngle(azimuth, azimuthFiltered)

        // Mettre a jour la boussole (toujours, sans throttle)
        updateCompassView()

        // Mettre a jour la carte (avec throttle)
        updateMapRotation()
    }

    /**
     * Copie les donnees des capteurs dans les tableaux
     */
    private fun copySensorData(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
            }
        }
    }

    /**
     * Calcule l'azimuth a partir des donnees des capteurs
     * Retourne null si le calcul echoue
     */
    private fun calculateAzimuth(): Float? {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )

        if (!success) {
            return null
        }

        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        return Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
    }

    /**
     * Met a jour la rotation de la vue boussole
     */
    private fun updateCompassView() {
        compassView.rotation = -azimuthFiltered
    }

    /**
     * Met a jour la rotation de la carte avec throttle et delta minimum
     */
    private fun updateMapRotation() {
        if (!rotateWithCompass) return

        val now = System.currentTimeMillis()
        if (now - lastMapRotationTime < AppConstants.Compass.MAP_ROTATION_INTERVAL_MS) {
            return
        }

        val newRotation = -azimuthFiltered
        val delta = abs(newRotation - lastSentRotation)
        val normalizedDelta = if (delta > 180f) 360f - delta else delta

        if (normalizedDelta >= AppConstants.Map.MIN_ROTATION_DELTA) {
            lastMapRotationTime = now
            lastSentRotation = newRotation
            onRotationChanged(newRotation)
        }
    }

    /**
     * Lisse un angle pour éviter les sauts brusques
     */
    /**
     * Lisse un angle pour eviter les sauts brusques
     */
    private fun smoothAngle(target: Float, current: Float): Float {
        var delta = target - current

        // Normaliser entre -180 et 180
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        // Seuil de securite pour eviter les tremblements
        if (abs(delta) < AppConstants.Compass.SMOOTH_ANGLE_THRESHOLD_MIN) {
            return current
        }

        // Calculer le pas avec interpolation
        var step = delta * AppConstants.Compass.SMOOTH_ALPHA

        // Garantir un pas minimum pour eviter les saccades en fin de rotation
        if (abs(step) < AppConstants.Compass.SMOOTH_MIN_STEP && abs(delta) >= AppConstants.Compass.SMOOTH_ANGLE_THRESHOLD_MIN) {
            step = AppConstants.Compass.SMOOTH_MIN_STEP * sign(delta)
        }

        return current + step
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
     * Lisse un angle avec une vitesse constante pour eviter les sauts brusques
     */
    /*private fun smoothAngle(target: Float, current: Float): Float {
        var delta = target - current

        // Normaliser entre -180 et 180
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        // Si le delta est tres petit, aller directement a la cible
        if (abs(delta) < AppConstants.Map.ROTATION_SPEED_DEGREES_PER_UPDATE) {
            return target
        }

        // Avancer a vitesse constante dans la direction de la cible
        val direction = if (delta > 0) 1f else -1f
        val smoothed = current + direction * AppConstants.Map.ROTATION_SPEED_DEGREES_PER_UPDATE

        return smoothed
    }*/

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

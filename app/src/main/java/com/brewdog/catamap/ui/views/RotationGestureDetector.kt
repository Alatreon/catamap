package com.brewdog.catamap.ui.views

import android.view.MotionEvent
import com.brewdog.catamap.constants.AppConstants
import com.brewdog.catamap.utils.logging.Logger
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Détecteur de gestes de rotation à deux doigts
 * Utilise un buffer de lissage pour des rotations fluides
 */
class RotationGestureDetector(
    val listener: (Float) -> Unit
) {

    companion object {
        private const val TAG = "RotationGestureDetector"
    }

    private var prevAngle = 0f
    private var isRotating = false
    private var rotationSmoothingBuffer = mutableListOf<Float>()
    private val bufferSize = AppConstants.Map.ROTATION_SMOOTHING_BUFFER_SIZE

    init {
        Logger.d(TAG, "RotationGestureDetector initialized with bufferSize=$bufferSize")
    }

    /**
     * Traite les événements tactiles
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    prevAngle = getAngle(event)
                    isRotating = true
                    rotationSmoothingBuffer.clear()
                    Logger.d(TAG, "Rotation started, initial angle: $prevAngle°")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount == 2) {
                    handleRotationMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount < 2 && isRotating) {
                    Logger.d(TAG, "Rotation ended")
                    isRotating = false
                    rotationSmoothingBuffer.clear()
                }
            }
        }
        return true
    }

    /**
     * Gère le mouvement de rotation
     */
    private fun handleRotationMove(event: MotionEvent) {
        val angle = getAngle(event)
        var delta = angle - prevAngle

        // Normaliser l'angle entre -180 et 180
        delta = normalizeAngle(delta)

        // Filtrer les mouvements trop brusques
        if (abs(delta) < AppConstants.Map.MAX_ROTATION_DELTA) {
            // Ajouter au buffer de lissage
            rotationSmoothingBuffer.add(delta)
            if (rotationSmoothingBuffer.size > bufferSize) {
                rotationSmoothingBuffer.removeAt(0)
            }

            // Calculer la moyenne pour un mouvement plus fluide
            val smoothedDelta = rotationSmoothingBuffer.average().toFloat()

            Logger.v(TAG, "Rotation: raw=$delta°, smoothed=$smoothedDelta°, buffer=${rotationSmoothingBuffer.size}")

            // Notifier le listener
            listener(smoothedDelta)
        } else {
            Logger.v(TAG, "Rotation delta too large, ignored: $delta°")
        }

        prevAngle = angle
    }

    /**
     * Calcule l'angle entre deux points tactiles
     */
    private fun getAngle(event: MotionEvent): Float {
        val dx = (event.getX(1) - event.getX(0)).toDouble()
        val dy = (event.getY(1) - event.getY(0)).toDouble()
        val angle = Math.toDegrees(atan2(dy, dx)).toFloat()
        
        Logger.v(TAG, "getAngle: dx=$dx, dy=$dy, angle=$angle°")
        
        return angle
    }

    /**
     * Normalise un angle entre -180 et 180
     */
    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        if (normalized > 180) normalized -= 360
        if (normalized < -180) normalized += 360
        return normalized
    }

    /**
     * Vérifie si une rotation est en cours
     */
    fun isRotating(): Boolean = isRotating

    /**
     * Reset le détecteur
     */
    fun reset() {
        Logger.d(TAG, "Resetting rotation detector")
        isRotating = false
        rotationSmoothingBuffer.clear()
        prevAngle = 0f
    }

    /**
     * Log l'état actuel
     */
    fun logState() {
        Logger.state(TAG, "RotationGestureDetector", mapOf(
            "isRotating" to isRotating,
            "prevAngle" to prevAngle,
            "bufferSize" to rotationSmoothingBuffer.size,
            "bufferCapacity" to bufferSize
        ))
    }
}

package com.brewdog.catamap

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(val listener: (Float) -> Unit) {
    private var prevAngle = 0f
    private var isRotating = false
    private var rotationSmoothingBuffer = mutableListOf<Float>()
    private val bufferSize = 3 // Lissage sur les 3 dernières valeurs

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    prevAngle = getAngle(event)
                    isRotating = true
                    rotationSmoothingBuffer.clear()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount == 2) {
                    val angle = getAngle(event)
                    var delta = angle - prevAngle

                    // Normaliser l'angle entre -180 et 180
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360

                    // Filtrer les mouvements trop brusques
                    if (kotlin.math.abs(delta) < 30f) {
                        // Ajouter au buffer de lissage
                        rotationSmoothingBuffer.add(delta)
                        if (rotationSmoothingBuffer.size > bufferSize) {
                            rotationSmoothingBuffer.removeAt(0)
                        }

                        // Calculer la moyenne pour un mouvement plus fluide
                        val smoothedDelta = rotationSmoothingBuffer.average().toFloat()

                        listener(smoothedDelta)
                    }

                    prevAngle = angle
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount < 2) {
                    isRotating = false
                    rotationSmoothingBuffer.clear()
                }
            }
        }
        return true
    }

    private fun getAngle(event: MotionEvent): Float {
        val dx = (event.getX(1) - event.getX(0)).toDouble()
        val dy = (event.getY(1) - event.getY(0)).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }
}
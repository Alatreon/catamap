package com.brewdog.catamap

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(val listener: (Float) -> Unit) {
    private var prevAngle = 0f
    private var isRotating = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    prevAngle = getAngle(event)
                    isRotating = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotating && event.pointerCount == 2) {
                    val angle = getAngle(event)
                    val delta = angle - prevAngle
                    listener(delta)
                    prevAngle = angle
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount < 2) isRotating = false
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

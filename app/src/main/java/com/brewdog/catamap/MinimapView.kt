package com.brewdog.catamap

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Vue personnalisée pour afficher la minimap avec viewport
 */
class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val VIEWPORT_STROKE_WIDTH_DP = 1f     // Épaisseur du cadre
        private const val VIEWPORT_COLOR = Color.RED              // Couleur du cadre
    }

    // ImageView pour afficher la minimap
    private val imageView: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY  // 🔧 CHANGÉ de FIT_CENTER à FIT_XY
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }
    private val viewportOverlay: ViewportOverlay

    // Callbacks
    var onViewportDragged: ((Float, Float) -> Unit)? = null

    init {
        addView(imageView)

        // Overlay pour dessiner le viewport
        viewportOverlay = ViewportOverlay(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }
        addView(viewportOverlay)

        // Fond semi-transparent
        setBackgroundColor(Color.argb(200, 0, 0, 0))

        // Forcer une taille minimale
        minimumWidth = (120 * resources.displayMetrics.density).toInt()
        minimumHeight = (120 * resources.displayMetrics.density).toInt()
    }

    /**
     * Définit l'image de la minimap
     */
    fun setMinimapImage(uri: android.net.Uri?) {
        if (uri != null) {
            imageView.setImageURI(uri)
        } else {
            imageView.setImageDrawable(null)
        }
    }

    /**
     * Met à jour le viewport (zone visible)
     */
    fun setViewport(viewport: RectF) {
        viewportOverlay.setViewport(viewport)
    }

    /**
     * Gestion des événements touch
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Vérifier si le touch est dans le viewport
                if (viewportOverlay.isPointInViewport(event.x, event.y)) {
                    viewportOverlay.isDragging = true
                    viewportOverlay.lastTouchX = event.x
                    viewportOverlay.lastTouchY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (viewportOverlay.isDragging) {
                    val dx = event.x - viewportOverlay.lastTouchX
                    val dy = event.y - viewportOverlay.lastTouchY

                    // Notifier le callback
                    onViewportDragged?.invoke(dx, dy)

                    viewportOverlay.lastTouchX = event.x
                    viewportOverlay.lastTouchY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                viewportOverlay.isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Overlay personnalisé pour dessiner le viewport
     */
    private inner class ViewportOverlay(context: Context) : android.view.View(context) {
        private var viewport: RectF? = null
        private val paint = Paint().apply {
            color = VIEWPORT_COLOR
            style = Paint.Style.STROKE
            strokeWidth = VIEWPORT_STROKE_WIDTH_DP * resources.displayMetrics.density
            isAntiAlias = true
        }
        var isDragging = false
        var lastTouchX = 0f
        var lastTouchY = 0f

        fun setViewport(rect: RectF) {
            viewport = rect
            invalidate()
        }

        fun isPointInViewport(x: Float, y: Float): Boolean {
            return viewport?.contains(x, y) ?: false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val vp = viewport ?: return

            // Dessiner le quadrilatère du viewport
            canvas.drawRect(vp, paint)
        }
    }
}

package com.brewdog.catamap.ui.annotation

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.brewdog.catamap.domain.annotation.models.AnnotationEdit
import com.brewdog.catamap.utils.logging.Logger

/**
 * Renderer pour les annotations
 * VERSION 2 : Support de l'alpha pour le drag & drop
 */
class AnnotationRenderer {

    companion object {
        private const val TAG = "AnnotationRenderer"
    }

    // Paint pour le texte (réutilisé)
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // Paint pour les dessins (réutilisé)
    private val pathPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Dessine un texte
     * @param canvas Canvas sur lequel dessiner
     * @param annotation Annotation texte à dessiner
     * @param isDarkMode Mode dark activé
     * @param position Position personnalisée (optionnel, pour drag)
     * @param alpha Transparence (0-255, optionnel, pour drag)
     */
    fun drawText(
        canvas: Canvas,
        annotation: AnnotationEdit.Text,
        isDarkMode: Boolean,
        position: PointF? = null,
        alpha: Int = 255
    ) {
        // Récupérer la couleur appropriée
        val color = annotation.color.getColor(isDarkMode)

        // Configurer le paint
        textPaint.color = color
        textPaint.textSize = annotation.fontSize
        textPaint.alpha = alpha

        // Position à utiliser
        val drawPosition = position ?: annotation.position

        // Dessiner le texte centré sur la position
        canvas.drawText(
            annotation.content,
            drawPosition.x,
            drawPosition.y,
            textPaint
        )

        Logger.v(TAG, "Drew text: \"${annotation.content}\" at $drawPosition (alpha=$alpha)")
    }

    /**
     * Dessine un chemin (dessin)
     */
    fun drawPath(canvas: Canvas, annotation: AnnotationEdit.Drawing, isDarkMode: Boolean) {
        if (annotation.points.isEmpty()) {
            Logger.v(TAG, "Skipping empty path")
            return
        }

        // Récupérer la couleur appropriée
        val color = annotation.color.getColor(isDarkMode)

        // Configurer le paint
        pathPaint.color = color
        pathPaint.strokeWidth = annotation.strokeWidth
        pathPaint.alpha = 255

        // Créer le chemin
        val path = Path()
        val firstPoint = annotation.points.first()
        path.moveTo(firstPoint.x, firstPoint.y)

        for (i in 1 until annotation.points.size) {
            val point = annotation.points[i]
            path.lineTo(point.x, point.y)
        }

        // Dessiner le chemin
        canvas.drawPath(path, pathPaint)

        Logger.v(TAG, "Drew path: ${annotation.points.size} points")
    }
}
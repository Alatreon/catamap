package com.brewdog.catamap.domain.annotation.models

import android.graphics.PointF
import android.graphics.RectF
import com.brewdog.catamap.utils.extensions.toLogString
import com.brewdog.catamap.utils.logging.Logger
import java.util.UUID

/**
 * Classe de base pour toutes les annotations
 * (Renommé AnnotationEdit pour éviter conflit avec android.app.Annotation)
 */
sealed class AnnotationEdit {
    abstract val id: String
    abstract val color: AnnotationColor
    abstract val timestamp: Long

    companion object {
        private const val TAG = "Annotation"
    }

    /**
     * Annotation de type texte
     *
     * @param id Identifiant unique
     * @param content Contenu du texte
     * @param position Position sur l'image (coordonnées image, pas écran)
     * @param fontSize Taille de police
     * @param color Couleur (light + dark)
     * @param timestamp Date de création
     */
    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        val content: String,
        val position: PointF,
        val fontSize: Float,
        override val color: AnnotationColor,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AnnotationEdit() {

        /**
         * Log l'état de l'annotation texte
         */
        fun logState() {
            Logger.state(TAG, "Text[$id]", mapOf(
                "content" to content,
                "position" to position.toLogString(),
                "fontSize" to fontSize,
                "timestamp" to timestamp
            ))
        }

        /**
         * Crée une copie avec une nouvelle position
         */
        fun withPosition(newPosition: PointF): Text {
            return copy(position = PointF(newPosition.x, newPosition.y))
        }

        /**
         * Crée une copie avec un nouveau contenu
         */
        fun withContent(newContent: String): Text {
            return copy(content = newContent)
        }
    }

    /**
     * Annotation de type dessin libre
     *
     * @param id Identifiant unique
     * @param points Liste des points du tracé (coordonnées image)
     * @param strokeWidth Épaisseur du trait
     * @param color Couleur (light + dark)
     * @param timestamp Date de création
     */
    data class Drawing(
        override val id: String = UUID.randomUUID().toString(),
        val points: List<PointF>,
        val strokeWidth: Float,
        override val color: AnnotationColor,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AnnotationEdit() {

        /**
         * Log l'état de l'annotation dessin
         */
        fun logState() {
            Logger.state(TAG, "Drawing[$id]", mapOf(
                "pointsCount" to points.size,
                "strokeWidth" to strokeWidth,
                "timestamp" to timestamp,
                "firstPoint" to (points.firstOrNull()?.toLogString() ?: "none"),
                "lastPoint" to (points.lastOrNull()?.toLogString() ?: "none")
            ))
        }

        /**
         * Calcule les bounds du dessin
         */
        fun getBounds(): RectF? {
            if (points.isEmpty()) return null

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            points.forEach { point ->
                minX = minOf(minX, point.x)
                minY = minOf(minY, point.y)
                maxX = maxOf(maxX, point.x)
                maxY = maxOf(maxY, point.y)
            }

            return RectF(minX, minY, maxX, maxY)
        }

        /**
         * Ajoute un point au tracé
         */
        fun addPoint(point: PointF): Drawing {
            return copy(points = points + PointF(point.x, point.y))
        }

        /**
         * Simplifie le tracé (réduit nombre de points)
         * Utile pour optimisation performance
         */
        fun simplify(tolerance: Float = 2.0f): Drawing {
            if (points.size <= 2) return this

            // Algorithme de simplification Douglas-Peucker
            val simplified = douglasPeucker(points, tolerance)

            Logger.d(TAG, "Simplified drawing: ${points.size} → ${simplified.size} points")

            return copy(points = simplified)
        }

        private fun douglasPeucker(points: List<PointF>, tolerance: Float): List<PointF> {
            if (points.size <= 2) return points

            // Trouver le point le plus éloigné de la ligne start-end
            var maxDistance = 0f
            var maxIndex = 0
            val start = points.first()
            val end = points.last()

            for (i in 1 until points.size - 1) {
                val distance = perpendicularDistance(points[i], start, end)
                if (distance > maxDistance) {
                    maxDistance = distance
                    maxIndex = i
                }
            }

            // Si le point est assez loin, diviser et récurser
            if (maxDistance > tolerance) {
                val left = douglasPeucker(points.subList(0, maxIndex + 1), tolerance)
                val right = douglasPeucker(points.subList(maxIndex, points.size), tolerance)

                // Combiner (sans dupliquer le point du milieu)
                return left.dropLast(1) + right
            }

            // Sinon, garder seulement start et end
            return listOf(start, end)
        }

        private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
            val dx = lineEnd.x - lineStart.x
            val dy = lineEnd.y - lineStart.y

            if (dx == 0f && dy == 0f) {
                // Ligne dégénérée
                val pdx = point.x - lineStart.x
                val pdy = point.y - lineStart.y
                return kotlin.math.sqrt(pdx * pdx + pdy * pdy)
            }

            val numerator = kotlin.math.abs(
                dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x
            )
            val denominator = kotlin.math.sqrt(dx * dx + dy * dy)

            return numerator / denominator
        }
    }
}
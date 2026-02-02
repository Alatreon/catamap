package com.brewdog.catamap.domain.annotation.tools

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Algorithme de Douglas-Peucker pour simplifier les tracés
 *
 * Réduit le nombre de points d'un tracé tout en conservant sa forme générale.
 * Particulièrement utile pour :
 * - Réduire la taille des fichiers JSON
 * - Améliorer les performances de rendu
 * - Lisser les tracés dessinés au doigt
 *
 * Exemple :
 * Points originaux : 300 points
 * Après simplification (epsilon=2.0) : 30 points (~90% de réduction)
 * Résultat visuel : Identique à l'œil nu
 */
object DouglasPeucker {

    /**
     * Simplifie une liste de points
     *
     * @param points Liste des points originaux
     * @param epsilon Tolérance (distance maximale en pixels)
     *                Plus epsilon est grand, plus la simplification est agressive
     *                Valeur recommandée : 2.0
     * @return Liste simplifiée de points
     */
    fun simplify(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) {
            // Pas besoin de simplifier
            return points
        }

        return simplifyRecursive(points, epsilon, 0, points.size - 1)
    }

    /**
     * Implémentation récursive de l'algorithme
     */
    private fun simplifyRecursive(
        points: List<PointF>,
        epsilon: Float,
        startIndex: Int,
        endIndex: Int
    ): List<PointF> {
        // Trouver le point le plus éloigné de la ligne start-end
        var maxDistance = 0f
        var maxIndex = startIndex

        for (i in startIndex + 1 until endIndex) {
            val distance = perpendicularDistance(
                points[i],
                points[startIndex],
                points[endIndex]
            )

            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }

        // Si le point le plus éloigné dépasse epsilon, subdiviser
        return if (maxDistance > epsilon) {
            // Le segment doit être subdivisé
            val leftSegment = simplifyRecursive(points, epsilon, startIndex, maxIndex)
            val rightSegment = simplifyRecursive(points, epsilon, maxIndex, endIndex)

            // Fusionner les deux segments (sans dupliquer le point du milieu)
            leftSegment.dropLast(1) + rightSegment
        } else {
            // Le segment peut être approximé par une ligne droite
            listOf(points[startIndex], points[endIndex])
        }
    }

    /**
     * Calcule la distance perpendiculaire d'un point à une ligne
     *
     * @param point Point à mesurer
     * @param lineStart Premier point de la ligne
     * @param lineEnd Dernier point de la ligne
     * @return Distance perpendiculaire en pixels
     */
    private fun perpendicularDistance(
        point: PointF,
        lineStart: PointF,
        lineEnd: PointF
    ): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y

        // Longueur de la ligne
        val norm = sqrt(dx * dx + dy * dy)

        if (norm == 0f) {
            // La ligne est en fait un point, retourner la distance directe
            val pdx = point.x - lineStart.x
            val pdy = point.y - lineStart.y
            return sqrt(pdx * pdx + pdy * pdy)
        }

        // Formule de la distance perpendiculaire
        // https://en.wikipedia.org/wiki/Distance_from_a_point_to_a_line
        return abs(
            dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x
        ) / norm
    }

    /**
     * Calcule le pourcentage de réduction
     * Utile pour le logging et le debug
     */
    fun calculateReduction(originalSize: Int, simplifiedSize: Int): Float {
        if (originalSize == 0) return 0f
        return ((originalSize - simplifiedSize).toFloat() / originalSize) * 100f
    }
}
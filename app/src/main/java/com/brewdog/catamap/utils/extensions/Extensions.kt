package com.brewdog.catamap.utils.extensions

import android.graphics.PointF
import android.view.View
import com.brewdog.catamap.utils.logging.Logger

/**
 * Extensions Kotlin pour simplifier le code
 */

// ========== VIEW EXTENSIONS ==========

/**
 * Rend la vue visible
 */
fun View.show() {
    visibility = View.VISIBLE
}

/**
 * Rend la vue invisible (garde l'espace)
 */
fun View.hide() {
    visibility = View.INVISIBLE
}

/**
 * Rend la vue gone (libère l'espace)
 */
fun View.gone() {
    visibility = View.GONE
}

/**
 * Toggle la visibilité
 */
fun View.toggleVisibility() {
    visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
}

/**
 * Vérifie si la vue est visible
 */
val View.isVisible: Boolean
    get() = visibility == View.VISIBLE

// ========== FLOAT EXTENSIONS ==========

/**
 * Limite une valeur entre min et max
 */
fun Float.coerceInRange(min: Float, max: Float): Float = coerceIn(min, max)

/**
 * Normalise un angle entre -180 et 180
 */
fun Float.normalizeAngle(): Float {
    var angle = this
    while (angle > 180) angle -= 360
    while (angle < -180) angle += 360
    return angle
}

// ========== POINTF EXTENSIONS ==========

/**
 * Crée une copie du PointF
 */
fun PointF.copy(): PointF = PointF(x, y)

/**
 * Formate le PointF pour les logs
 */
fun PointF.toLogString(): String = "(x=%.2f, y=%.2f)".format(x, y)

// ========== NULLABLE EXTENSIONS ==========

/**
 * Execute le bloc seulement si non null et log si null
 */
inline fun <T> T?.ifNotNullOrLog(tag: String, message: String, block: (T) -> Unit) {
    if (this != null) {
        block(this)
    } else {
        Logger.w(tag, message)
    }
}

// ========== TIMING EXTENSIONS ==========

/**
 * Mesure le temps d'exécution d'un bloc
 */
inline fun <T> measureTimeWithResult(tag: String, operation: String, block: () -> T): T {
    val start = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val duration = System.currentTimeMillis() - start
        Logger.perf(tag, operation, duration)
    }
}

/**
 * Mesure le temps d'exécution sans retour
 */
inline fun measureTime(tag: String, operation: String, block: () -> Unit) {
    measureTimeWithResult(tag, operation, block)
}

// ========== COLLECTION EXTENSIONS ==========

/**
 * Log le contenu d'une liste
 */
fun <T> List<T>.logContent(tag: String, prefix: String = "List") {
    Logger.d(tag, "$prefix size=${this.size}, items=${this.joinToString(", ")}")
}

/**
 * Log le contenu d'une map
 */
fun <K, V> Map<K, V>.logContent(tag: String, prefix: String = "Map") {
    Logger.d(tag, "$prefix size=${this.size}, entries=${this.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
}

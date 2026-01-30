package com.brewdog.catamap.domain.annotation.models

import com.brewdog.catamap.utils.logging.Logger
import java.util.UUID

/**
 * Représente un calque (layer) contenant des annotations
 *
 * @param id Identifiant unique
 * @param name Nom du calque (unique par carte)
 * @param isVisible Visibilité du calque
 * @param zIndex Ordre d'affichage (plus élevé = au-dessus)
 * @param annotations Liste des annotations du calque
 */
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var isVisible: Boolean = true,
    var zIndex: Int,
    val annotations: MutableList<AnnotationEdit> = mutableListOf()
) {
    companion object {
        private const val TAG = "Layer"

        /**
         * Crée un calque par défaut
         */
        fun createDefault(name: String, zIndex: Int): Layer {
            Logger.d(TAG, "Creating default layer: $name, zIndex=$zIndex")
            return Layer(
                name = name,
                isVisible = true,
                zIndex = zIndex
            )
        }
    }

    /**
     * Ajoute une annotation au calque
     */
    fun addAnnotation(annotation: AnnotationEdit) {
        Logger.entry(TAG, "addAnnotation", annotation.id)
        annotations.add(annotation)
        Logger.i(TAG, "Annotation added to layer '$name': ${annotations.size} total")
    }

    /**
     * Supprime une annotation du calque
     */
    fun removeAnnotation(annotationId: String): Boolean {
        Logger.entry(TAG, "removeAnnotation", annotationId)
        val removed = annotations.removeIf { it.id == annotationId }
        if (removed) {
            Logger.i(TAG, "Annotation removed from layer '$name': ${annotations.size} remaining")
        } else {
            Logger.w(TAG, "Annotation not found: $annotationId")
        }
        return removed
    }

    /**
     * Trouve une annotation par son ID
     */
    fun findAnnotation(annotationId: String): AnnotationEdit? {
        return annotations.find { it.id == annotationId }
    }

    /**
     * Vérifie si le calque est vide
     */
    fun isEmpty(): Boolean = annotations.isEmpty()

    /**
     * Vérifie si le calque contient des annotations
     */
    fun isNotEmpty(): Boolean = annotations.isNotEmpty()

    /**
     * Compte le nombre d'annotations par type
     */
    fun getAnnotationCounts(): AnnotationCounts {
        var textCount = 0
        var drawingCount = 0

        annotations.forEach { annotation ->
            when (annotation) {
                is AnnotationEdit.Text -> textCount++
                is AnnotationEdit.Drawing -> drawingCount++
            }
        }

        return AnnotationCounts(textCount, drawingCount)
    }

    /**
     * Détermine le type d'icône à afficher pour ce calque
     */
    fun getLayerType(): LayerType {
        val counts = getAnnotationCounts()

        return when {
            counts.total == 0 -> LayerType.EMPTY
            counts.textCount > 0 && counts.drawingCount > 0 -> LayerType.MIXED
            counts.textCount > 0 -> LayerType.TEXT_ONLY
            counts.drawingCount > 0 -> LayerType.DRAWING_ONLY
            else -> LayerType.EMPTY
        }
    }

    /**
     * Efface toutes les annotations du calque
     */
    fun clear() {
        Logger.entry(TAG, "clear")
        val count = annotations.size
        annotations.clear()
        Logger.i(TAG, "Layer '$name' cleared: $count annotations removed")
    }

    /**
     * Crée une copie du calque
     */
    fun duplicate(newName: String): Layer {
        Logger.entry(TAG, "duplicate", newName)

        return Layer(
            id = UUID.randomUUID().toString(),
            name = newName,
            isVisible = isVisible,
            zIndex = zIndex,
            annotations = annotations.toMutableList() // Copie des annotations
        ).also {
            Logger.i(TAG, "Layer duplicated: '$name' → '$newName'")
        }
    }

    /**
     * Log l'état du calque
     */
    fun logState() {
        val counts = getAnnotationCounts()
        Logger.state(TAG, "Layer[$name]", mapOf(
            "id" to id,
            "isVisible" to isVisible,
            "zIndex" to zIndex,
            "annotations" to counts.total,
            "text" to counts.textCount,
            "drawing" to counts.drawingCount,
            "type" to getLayerType()
        ))
    }
}

/**
 * Compteur d'annotations par type
 */
data class AnnotationCounts(
    val textCount: Int,
    val drawingCount: Int
) {
    val total: Int get() = textCount + drawingCount

    override fun toString(): String {
        return "Total: $total (Text: $textCount, Drawing: $drawingCount)"
    }
}

/**
 * Type de calque selon son contenu
 */
enum class LayerType {
    EMPTY,          // Pas d'annotations
    TEXT_ONLY,      // Seulement du texte
    DRAWING_ONLY,   // Seulement des dessins
    MIXED           // Texte + dessins
}
package com.brewdog.catamap.constants

/**
 * Constantes pour les outils d'annotation
 * À ajouter dans votre fichier AppConstants.kt existant
 */
object AnnotationConstants {

    /**
     * Constantes pour le dessin
     */
    object Drawing {
        const val MIN_STROKE_WIDTH = 1f
        const val MAX_STROKE_WIDTH = 20f
        const val DEFAULT_STROKE_WIDTH = 3f
    }

    /**
     * Constantes pour le texte
     */
    object Text {
        // Tailles en sp
        const val SIZE_SMALL = 24
        const val SIZE_MEDIUM = 32
        const val SIZE_LARGE = 40
        const val SIZE_XLARGE = 48

        val SIZES = listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE, SIZE_XLARGE)
        val SIZE_LABELS = listOf("Petit", "Moyen", "Grand", "Très grand")

        const val DEFAULT_SIZE = SIZE_SMALL
    }

    /**
     * Constantes pour les couleurs
     */
    object Colors {
        const val DEFAULT_COLOR = 0xFFFF0000.toInt() // Rouge
    }

    /**
     * Constantes pour l'overlay des outils
     */
    object ToolsOverlay {
        const val ANIMATION_DURATION_MS = 200L
        const val INDICATOR_BAR_HEIGHT_DP = 48
        const val TOOL_BAR_HEIGHT_DP = 72
    }
    /**
     * Constantes pour l'outil gomme
     */
    object Eraser {
        const val MIN_ERASER_SIZE = 10f
        const val MAX_ERASER_SIZE = 50f
        const val DEFAULT_ERASER_SIZE = 30f
        const val DETECTION_TOLERANCE = 15f
    }
}